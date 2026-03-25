package skuber.operator.zio

import zio.*

object ZControllerManager:
  /** Run all controllers in parallel. Fails if any controller fails.
   *  Never returns normally — runs until process exit or fatal error. */
  def run(controllers: List[ZController[?]]): ZIO[Any, Throwable, Nothing] =
    // Each _.run is ZIO[Any, Throwable, Nothing]; collectAllParDiscard completes when all finish (never).
    ZIO.collectAllParDiscard(controllers.map(_.run)) *> ZIO.never
