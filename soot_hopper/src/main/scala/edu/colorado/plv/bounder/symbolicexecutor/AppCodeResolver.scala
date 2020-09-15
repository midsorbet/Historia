package edu.colorado.plv.bounder.symbolicexecutor

import edu.colorado.plv.bounder.ir.{AppLoc, CallbackMethodInvoke, CallinMethodReturn, IRWrapper, InternalMethodReturn, JimpleFlowdroidWrapper, JimpleMethodLoc, Loc, MethodLoc, UnresolvedMethodTarget}

import scala.io.Source
import scala.util.matching.Regex

trait AppCodeResolver {
  def isFrameworkClass(packageName:String):Boolean
  def resolveCallLocation(tgt : UnresolvedMethodTarget): Loc
  def resolveCallbackEntry(method : MethodLoc):Option[Loc]
}

class DefaultAppCodeResolver[M,C] (ir: IRWrapper[M,C]) extends AppCodeResolver {
  protected val frameworkExtPath = getClass.getResource("/FrameworkExtensions.txt").getPath
  protected val extensionStrings: Regex = Source.fromFile(frameworkExtPath).getLines.mkString("|").r
  def isFrameworkClass(packageName:String):Boolean = packageName match{
    case extensionStrings() => true
    case _ => false
  }

  override def resolveCallLocation(tgt: UnresolvedMethodTarget): Loc = tgt match{
    case UnresolvedMethodTarget(clazz, method, _) if isFrameworkClass(clazz) => CallinMethodReturn(clazz, method)
    case UnresolvedMethodTarget(clazz, method, Some(methodloc: MethodLoc)) => InternalMethodReturn(clazz, method, methodloc)
  }

  override def resolveCallbackEntry(method: MethodLoc): Option[Loc] = {
    val overrides = ir.getOverrideChain(method)
    if (overrides.size > 0) {
      val leastPrecise: MethodLoc = overrides.last
      Some(CallbackMethodInvoke(leastPrecise.classType, leastPrecise.simpleName, method))
    } else None

  }
}