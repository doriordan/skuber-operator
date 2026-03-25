package skuber.operator.zio

import zio.*

object ZControllerManager:
  /** Run all controllers in parallel. Fails if any controller fails.
   *  Never returns normally — runs until process exit or fatal error. */
  def run(controllers: List[ZController[?]]): ZIO[Any, Throwable, Nothing] =
    if controllers.isEmpty then
      ZIO.die(new IllegalArgumentException("ZControllerManager.run requires at least one controller"))
    else
      // collectAllParDiscard returns ZIO[..., Unit]; *> ZIO.never widens the type to Nothing.
      // In practice this is only reachable if all controllers complete (impossible — each runs forever).
      ZIO.collectAllParDiscard(controllers.map(_.run)) *> ZIO.never
