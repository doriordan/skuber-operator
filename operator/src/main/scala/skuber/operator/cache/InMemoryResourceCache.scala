package skuber.operator.cache

import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import skuber.model.{LabelSelector, ObjectResource}
import skuber.operator.reconciler.NamespacedName

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*

/**
 * Thread-safe in-memory implementation of ResourceCache.
 * Uses TrieMap for lock-free concurrent access.
 */
class InMemoryResourceCache[R <: ObjectResource]()(using mat: Materializer) extends ResourceCache[R]:

  // Primary store: NamespacedName -> Resource
  private val store = TrieMap[NamespacedName, R]()

  // Secondary indexes: indexName -> (indexValue -> Set[NamespacedName])
  private val indexes = TrieMap[String, TrieMap[String, Set[NamespacedName]]]()

  // Indexer functions: indexName -> indexer
  private val indexers = TrieMap[String, R => List[String]]()

  // Owner index for getOwnedBy queries
  private val ownerIndex = TrieMap[String, Set[NamespacedName]]()

  @volatile private var synced: Boolean = false

  // Event broadcasting
  private val (eventQueue, eventSource) = Source
    .queue[CacheEvent[R]](bufferSize = 1024, OverflowStrategy.dropHead)
    .toMat(BroadcastHub.sink(bufferSize = 256))(Keep.both)
    .run()

  // Some methods available to clients of this cache either mutate or read multiple values (store, indexes etc.)
  // - this read/write lock wraps such methods to ensure atomicity so clients don't have to worry about inconsistent
  // results
  private val lock = new ReentrantReadWriteLock()

  private inline def withReadLock[A](body: => A): A =
    lock.readLock().lock()
    try body
    finally lock.readLock().unlock()

  private inline def withWriteLock[A](body: => A): A =
    lock.writeLock().lock()
    try body
    finally lock.writeLock().unlock()

  def hasSynced: Boolean = synced

  def markSynced(): Unit =
    synced = true
    eventQueue.offer(CacheEvent.Synced)

  def get(name: String): Option[R] =
    // Without namespace, try empty namespace (for cluster-scoped)
    store.get(NamespacedName("", name))

  def get(key: NamespacedName): Option[R] =
    store.get(key)

  def list(): List[R] = withReadLock {
    store.values.toList
  }

  def list(selector: LabelSelector): List[R] = withReadLock {
    store.values.filter(matchesSelector(_, selector)).toList
  }

  def listInNamespace(namespace: String): List[R] = withReadLock {
    store.values.filter(_.metadata.namespace == namespace).toList
  }

  def events: Source[CacheEvent[R], ?] = eventSource

  def addIndex(name: String, indexer: R => List[String]): Unit = withWriteLock {
    indexers.put(name, indexer)
    indexes.put(name, TrieMap.empty)
    // Rebuild index for existing resources
    store.values.foreach(r => updateIndex(name, indexer, r, None))
  }

  def byIndex(indexName: String, value: String): List[R] = withReadLock {
    indexes.get(indexName).flatMap(_.get(value)) match
      case Some(keys) => keys.toList.flatMap(store.get)
      case None => Nil
  }

  def getOwnedBy(ownerUid: String): List[R] = withReadLock {
    ownerIndex.get(ownerUid) match
      case Some(keys) => keys.toList.flatMap(store.get)
      case None => Nil
  }

  /**
   * Add a resource to the cache.
   */
  def add(resource: R): Unit = withWriteLock {
    val key = NamespacedName(resource)
    val existing = store.put(key, resource)
    updateAllIndexes(resource, existing)
    updateOwnerIndex(resource, existing)
    existing match
      case Some(old) => eventQueue.offer(CacheEvent.Updated(old, resource))
      case None => eventQueue.offer(CacheEvent.Added(resource))
  }

  /**
   * Update a resource in the cache.
   */
  def update(resource: R): Unit = withWriteLock {
    val key = NamespacedName(resource)
    val existing = store.put(key, resource)
    updateAllIndexes(resource, existing)
    updateOwnerIndex(resource, existing)
    existing match
      case Some(old) => eventQueue.offer(CacheEvent.Updated(old, resource))
      case None => eventQueue.offer(CacheEvent.Added(resource))
  }

  /**
   * Delete a resource from the cache.
   */
  def delete(resource: R): Unit = withWriteLock {
    val key = NamespacedName(resource)
    store.remove(key).foreach { old =>
      removeFromAllIndexes(old)
      removeFromOwnerIndex(old)
      eventQueue.offer(CacheEvent.Deleted(old))
    }
  }

  /**
   * Replace all resources in the cache.
   * Used during initial list or resync.
   */
  def replace(resources: List[R]): Unit = withWriteLock {
    val newKeys = resources.map(NamespacedName(_)).toSet
    val oldKeys = store.keySet.toSet

    // Remove resources no longer present
    (oldKeys -- newKeys).foreach { key =>
      store.remove(key).foreach { old =>
        removeFromAllIndexes(old)
        removeFromOwnerIndex(old)
        eventQueue.offer(CacheEvent.Deleted(old))
      }
    }

    // Add or update resources (write lock is reentrant so add() can re-acquire)
    resources.foreach(add)
  }

  private def matchesSelector(resource: R, selector: LabelSelector): Boolean =
    val labels = resource.metadata.labels
    selector.requirements.forall { req =>
      req match
        case LabelSelector.ExistsRequirement(key) =>
          labels.contains(key)
        case LabelSelector.NotExistsRequirement(key) =>
          !labels.contains(key)
        case LabelSelector.IsEqualRequirement(key, value) =>
          labels.get(key).contains(value)
        case LabelSelector.IsNotEqualRequirement(key, value) =>
          !labels.get(key).contains(value)
        case LabelSelector.InRequirement(key, values) =>
          labels.get(key).exists(values.contains)
        case LabelSelector.NotInRequirement(key, values) =>
          !labels.get(key).exists(values.contains)
    }

  private def updateAllIndexes(resource: R, existing: Option[R]): Unit =
    indexers.foreach { case (name, indexer) =>
      updateIndex(name, indexer, resource, existing)
    }

  private def updateIndex(name: String, indexer: R => List[String], resource: R, existing: Option[R]): Unit =
    val key = NamespacedName(resource)
    val index = indexes.getOrElseUpdate(name, TrieMap.empty)

    // Remove from old index values
    existing.foreach { old =>
      indexer(old).foreach { value =>
        index.updateWith(value) {
          case Some(keys) => Some(keys - key)
          case None => None
        }
      }
    }

    // Add to new index values
    indexer(resource).foreach { value =>
      index.updateWith(value) {
        case Some(keys) => Some(keys + key)
        case None => Some(Set(key))
      }
    }

  private def removeFromAllIndexes(resource: R): Unit =
    val key = NamespacedName(resource)
    indexers.foreach { case (name, indexer) =>
      indexes.get(name).foreach { index =>
        indexer(resource).foreach { value =>
          index.updateWith(value) {
            case Some(keys) =>
              val remaining = keys - key
              if remaining.isEmpty then None else Some(remaining)
            case None => None
          }
        }
      }
    }

  private def updateOwnerIndex(resource: R, existing: Option[R]): Unit =
    val key = NamespacedName(resource)

    // Remove from old owner refs
    existing.foreach { old =>
      old.metadata.ownerReferences.foreach { ref =>
        ownerIndex.updateWith(ref.uid) {
          case Some(keys) => Some(keys - key)
          case None => None
        }
      }
    }

    // Add to new owner refs
    resource.metadata.ownerReferences.foreach { ref =>
      ownerIndex.updateWith(ref.uid) {
        case Some(keys) => Some(keys + key)
        case None => Some(Set(key))
      }
    }

  private def removeFromOwnerIndex(resource: R): Unit =
    val key = NamespacedName(resource)
    resource.metadata.ownerReferences.foreach { ref =>
      ownerIndex.updateWith(ref.uid) {
        case Some(keys) =>
          val remaining = keys - key
          if remaining.isEmpty then None else Some(remaining)
        case None => None
      }
    }
