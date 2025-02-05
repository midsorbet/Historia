package edu.colorado.plv.bounder.symbolicexecutor

import better.files.Resource
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.BounderUtil.{derefNameOf, findFirstDerefFor}
import edu.colorado.plv.bounder.ir.{AppLoc, AssignCmd, CBEnter, CBExit, CIEnter, CIExit, CallbackMethodInvoke, CallbackMethodReturn, CallinMethodInvoke, CallinMethodReturn, CmdWrapper, EmptyTypeSet, FieldReference, Goto, GroupedCallinMethodInvoke, GroupedCallinMethodReturn, IRWrapper, InternalMethodInvoke, InternalMethodReturn, Invoke, InvokeCmd, JimpleMethodLoc, LVal, LineLoc, Loc, LocalWrapper, MethodLoc, NopCmd, NullConst, ReturnCmd, SkippedInternalMethodInvoke, SkippedInternalMethodReturn, SootWrapper, SpecialInvoke, StaticFieldReference, StaticInvoke, SwitchCmd, ThrowCmd, TopTypeSet, TypeSet, UnresolvedMethodTarget, VirtualInvoke}
import edu.colorado.plv.bounder.lifestate.LifeState.{OAbsMsg, Signature}
import edu.colorado.plv.bounder.lifestate.{LifecycleSpec, RxJavaSpec, SAsyncTask, SJavaThreading, SpecSignatures, ViewSpec}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.state.{AllReceiversNonNull, ConcreteVal, DirectInitialQuery, FieldPtEdge, HeapPtEdge, InitialQuery, NullVal, PureExpr, PureVal, PureVar, Qry, ReceiverNonNull, State, StaticPtEdge}
import scalaz.Memo

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.{ImmutableIterableIsParallelizable, IterableIsParallelizable}
import scala.util.matching.Regex
import scala.util.Random

//trait AppCodeResolver {
//  def sampleDeref(packageFilter:Option[String]):AppLoc
//  def callbackInComponent(loc: Loc): Boolean
//  def appMethods: Set[MethodLoc]
//  def isFrameworkClass(packageName: String): Boolean
//
//  def isAppClass(fullClassName: String): Boolean
//
//  def resolveCallLocation(tgt: UnresolvedMethodTarget): Set[Loc]
//
//  def resolveCallbackExit(method: MethodLoc, retCmdLoc: Option[LineLoc]): Option[Loc]
//
//  def resolveCallbackEntry(method: MethodLoc): Option[Loc]
//
//
//  def cellMayBeWritten(edge: HeapPtEdge, state:State):Boolean
//  def fieldMayBeWritten(field: (HeapPtEdge, PureExpr), state:State):Boolean
//
//  def getCallbacks: Set[MethodLoc]
//
//
//  def heuristicCiFlowsToDeref[M, C](messages: Set[OAbsMsg], filter: Option[String],
//                                    abs: AbstractInterpreter[M, C]): Set[InitialQuery]
//  // heuristic to find dereference of field in a ui callback like onClick
//  def heuristicDerefNullFinish[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[InitialQuery]
//  def heuristicDerefNullSynch[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[InitialQuery]
//  def derefFromField[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[Qry]
//  def derefFromCallin[M,C](callins: Set[OAbsMsg], filter:Option[String], abs:AbstractInterpreter[M,C]):Set[InitialQuery]
//
//  def allDeref[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[Qry]
//  def nullValueMayFlowTo[M,C](sources: Iterable[AppLoc],
//                         cfRes: ControlFlowResolver[M, C]): Set[AppLoc]
//
//  def matchesCI(i: Invoke, cbMsg:Iterable[OAbsMsg])(implicit ch:ClassHierarchyConstraints): Set[OAbsMsg]
//}
object FrameworkExtensions{
  private val urlPos = List("FrameworkExtensions.txt",
    "/Resources/FrameworkExtensions.txt",
    "Resources/FrameworkExtensions.txt")
  private val frameworkExtPaths: Seq[String] = urlPos.flatMap{ p =>
    try {
      Some(Resource.getUrl(p).getPath)
    }catch {
      case _:Throwable=>
        None
    }
  }
  val extensionStrings: List[String] = SootWrapper.cgEntryPointName::
    urlPos.flatMap{ (frameworkExtPath:String) =>
//    val source = Source.fromFile(frameworkExtPath)
    try {
      val source = Resource.getAsString(frameworkExtPath)
      Some(source.split("\n").toList)
    }catch{
      case _:Throwable => None
    }
  }.head

  val extensionRegex: Regex = extensionStrings.mkString("|").r
}

case class FilterResolver[M,C](component:Option[Seq[String]]){
  private val METHODPOSIDENT = "method:"
  private val useMethod = component.exists(_.exists(s => s.startsWith(METHODPOSIDENT)))
  private val methodFilterPos = {
    val applicable = component.getOrElse(Seq()).filter{c => c.startsWith(METHODPOSIDENT)}
    if(applicable.isEmpty) Seq(".*".r) else{
      applicable.map{a => a.drop(METHODPOSIDENT.size).r}
    }
  }
  private val (componentPos, componentNeg) = component match{
    case Some(filters) =>
      val spl = filters.groupBy(_.startsWith("!"))
      (spl.getOrElse(false, Nil).map(_.r), spl.getOrElse(true, Nil).map(_.tail).map(_.r))
    case None => (List(".*".r), List())
  }


  private def iDirectCallsGraph(ir:IRWrapper[M,C],loc:MethodLoc):Set[Loc] = {
    val unresolvedTargets = ir.makeMethodTargets(loc).map(callee =>
      UnresolvedMethodTarget(callee.classType, callee.simpleName, Set(callee)))
    unresolvedTargets.flatMap(target => ir.getAppCodeResolver.resolveCallLocation(target))
  }
  def directCallsGraph = Memo.mutableHashMapMemo((v:(IRWrapper[M,C],MethodLoc) )=> iDirectCallsGraph(v._1, v._2))
  /**
   *
   * @param loc calling method
   * @param includeCallin should result include callins
   * @return methods that may be called by loc
   */
  def callsToRetLoc(ir:IRWrapper[M,C], loc: MethodLoc, includeCallin: Boolean): Set[MethodLoc] = {
    val directCalls = directCallsGraph(ir, loc)
    val internalCalls = directCalls.flatMap {
      case InternalMethodReturn(_, _, oloc) =>
        // We only care about direct calls, calls through framework are considered callbacks
        if (!ir.getAppCodeResolver.isFrameworkClass(oloc.classType))
          Some(oloc)
        else if (includeCallin) Some(oloc) else None
      case CallinMethodReturn(sig) if includeCallin =>
        val res = ir.findMethodLoc(sig)
        res
      case _ =>
        None
    }
    internalCalls
  }

  //TODO: Warning untested method!!!
  def computeAllCallsIncludeCallin(ir:IRWrapper[M,C], loc: MethodLoc): Set[MethodLoc] = {
    val includeCallin = true
    val empty = Set[MethodLoc]()
    val out = BounderUtil.graphFixpoint[MethodLoc, Set[MethodLoc]](Set(loc),
      empty,
      empty,
      next = c => callsToRetLoc(ir,c, includeCallin),
      comp = (_, v) => callsToRetLoc(ir,v, includeCallin),
      join = (a, b) => a.union(b)
    )
    out.flatMap {
      case (k, v) => v
    }.toSet
  }
  def computeAllCalls(ir:IRWrapper[M,C],loc:MethodLoc):Set[MethodLoc] = {
    callsToRetLoc(ir,loc, false)
  }

  /**
   * Direct calls of method
   * @return
   */
  def allCallsApp: ((IRWrapper[M,C],MethodLoc)) => Set[MethodLoc] =
    Memo.mutableHashMapMemo((c: (IRWrapper[M,C],MethodLoc)) => computeAllCalls(c._1,c._2))

  def mayRecurse(ir:IRWrapper[M,C],m:MethodLoc):Boolean = {
    allCallsAppTransitive(ir,m, false).contains(m)
  }

  /**
   *
   * @param v (ir, method, shouldFilter)
   * @return set of methods in the app
   */
  private def computeAllCallsTransitive(v:(IRWrapper[M,C],MethodLoc,Boolean)):Set[MethodLoc] = {
    val ir = v._1
    val loc = v._2
    val shouldFilter = v._3
    val calls = mutable.Set[MethodLoc]()
    calls.addAll(allCallsApp(ir,loc).filter{c => !shouldFilter || methodLocMatchesComponent(c)})
    var added = true
    while(added){
      val newCalls =
        calls.par.flatMap{called => allCallsApp(ir,called)}.filter{
          c => !shouldFilter || methodLocMatchesComponent(c)
        }
      added = newCalls.exists{newCall => !calls.contains(newCall)}
      calls.addAll(newCalls)
    }
    calls.toSet
  }

  def allCallsAppTransitive:((IRWrapper[M,C],MethodLoc, Boolean)) => Set[MethodLoc] =
    Memo.mutableHashMapMemo{case (ir,m, shouldFilter) => computeAllCallsTransitive((ir,m,shouldFilter))}

  private val irCache = mutable.Map[IRWrapper[M,C], Set[MethodLoc]]()
  def methodLocMatchesComponent(methodLoc:MethodLoc):Boolean = {
    val className = methodLoc.classType
    val mName = methodLoc.simpleName
    lazy val pos = componentPos.exists(p => p.matches(className))
    lazy val neg = componentNeg.forall(n => !n.matches(className))
    lazy val name = methodFilterPos.exists { m => m.matches(mName) }
    pos && neg && name
  }
  def methodLocInComponent(loc:MethodLoc,ir:IRWrapper[M,C]):Boolean = {
    irCache.get(ir) match {
      case Some(value) => value.contains(loc)
      case None =>
        val resolver = ir.getAppCodeResolver
        val callbacks = resolver.getCallbacks.filter{methodLocMatchesComponent}
        val called: Set[MethodLoc] = callbacks.flatMap{ cb => allCallsAppTransitive(ir, cb, true) }.filter{methodLocMatchesComponent}
        val reachableMethodsInComponent = callbacks ++ called
        irCache.put(ir,reachableMethodsInComponent)

        if(useMethod){
          // print dbg callgraph
          ir.getAppCodeResolver.appMethods.filter(methodLocMatchesComponent).foreach{m =>
            if(!callbacks.contains(m)){
              val allCallSites = ir.appCallSites(m).map{ c => c.method}.toSet
              val callSitesInPkg =allCallSites.intersect(reachableMethodsInComponent)
              if(allCallSites.size > callSitesInPkg.size) {
                println(s"method: ${m} has filtered call sites including:")
                allCallSites.removedAll(callSitesInPkg).take(10).foreach{ callSite =>
                  println(s"    ${callSite}")
                }
              }
            }
          }

        }
        reachableMethodsInComponent.contains(loc)
    }
  }

  //  private val visitedMethodLoc = mutable.Set[MethodLoc]()
//  val methodLocInComponent:((MethodLoc, IRWrapper[M,C])) => Boolean =
//    Memo.mutableHashMapMemo {arg:(MethodLoc,IRWrapper[M,C]) =>
//      val methodLoc = arg._1
//      val ir = arg._2
//      val inCg = {
//        if(ir.getAppCodeResolver.getCallbacks.contains(methodLoc)){
//          true
//        }else{
//          val callers = ir.appCallSites(methodLoc)
//            .filter{caller => !visitedMethodLoc.contains(caller.method)} // ignore recursion
//          visitedMethodLoc.add(methodLoc)
//          callers.exists{caller => methodLocInComponent(caller.method,ir)}
//        }
//      }
//      val className = methodLoc.classType
//      val mName = methodLoc.simpleName
//      lazy val pos = componentPos.exists(p => p.matches(className))
//      lazy val neg = componentNeg.forall(n => !n.matches(className))
//      lazy val name = methodFilterPos.exists{m => m.matches(mName) }
//      inCg && pos && neg && name
//  }
  def locInComponent(loc: Loc, ir:IRWrapper[M,C]): Boolean = loc match {
    case CallbackMethodInvoke(_, methodLoc) => methodLocInComponent(methodLoc,ir)
    case CallbackMethodReturn(_, methodLoc, _) => methodLocInComponent(methodLoc,ir)
    case InternalMethodReturn(_,_,methodLoc) => methodLocInComponent(methodLoc,ir)
    case InternalMethodReturn(_, _, methodLoc) =>  methodLocInComponent(methodLoc,ir)
    case AppLoc(methodLoc, _,_) => methodLocInComponent(methodLoc,ir)
    case CallinMethodInvoke(_) => false
    case CallinMethodReturn(_) => false
    case GroupedCallinMethodInvoke(_, _) => false
    case GroupedCallinMethodReturn(_, _) => false
    case SkippedInternalMethodInvoke(_, _, loc) => methodLocInComponent(loc,ir)
    case SkippedInternalMethodReturn(_, _, _, loc) => methodLocInComponent(loc,ir)
    case _ => throw new IllegalStateException("callbackInComponent should only be called on callback returns")
  }
  def computeFieldsLookup(ir:IRWrapper[M,C]):FieldsLookup = {
    val resolver = ir.getAppCodeResolver
    val staticFields = mutable.Map[(String, String), (TypeSet, Set[MethodLoc])]()
    val excludeStaticFields = mutable.Map[(String, String), (TypeSet, Set[MethodLoc])]()
    val dynamicFields = mutable.Map[String, Set[(TypeSet, TypeSet, Set[MethodLoc])]]()
    val excludeDynamicFields = mutable.Map[String, Set[(TypeSet, TypeSet, Set[MethodLoc])]]()
    val constStaticFields = mutable.Map[(String,String), PureVal]()

    resolver.appMethods.foreach {
      case appMethod if methodLocInComponent(appMethod,ir)=>
          ir.findInMethod(appMethod.classType, appMethod.simpleName, {
            case AssignCmd(_, StaticFieldReference(declaringClass, fieldName, _, Some(v)), loc) =>
              constStaticFields.addOne((declaringClass,fieldName), v)
              false
            case AssignCmd(StaticFieldReference(declaringClass, fieldName, _,_), source, loc) =>
              val sourcePts = ir.pointsToSet(appMethod, source)
              val staticFieldsKey = (declaringClass, fieldName)
              val (oldStaticFieldsPt,oldSet:Set[MethodLoc]) = staticFields.getOrElse(staticFieldsKey, (EmptyTypeSet, Set.empty))
              val newStaticFieldsPt = oldStaticFieldsPt.union(sourcePts)
              val newSet:Set[MethodLoc] = oldSet + appMethod
              staticFields.put(staticFieldsKey, (newStaticFieldsPt,newSet))
              false
            case AssignCmd(FieldReference(base, _, _, name), tgt, _) =>
              //if(name == "media" || name == "callback"){
              //println()
              //}
              val basePts = ir.pointsToSet(appMethod, base)
              val tgtPts = ir.pointsToSet(appMethod, tgt)
              val oldFieldPtsList = dynamicFields.getOrElse(name, Set.empty)
              val applicable = oldFieldPtsList.find { case (pt1, pt2, _) => pt1.contains(basePts) && pt2.contains(tgtPts)}
              applicable match{
                case None =>
                  val toAdd: (TypeSet, TypeSet, Set[MethodLoc]) = (basePts, tgtPts, Set(appMethod))
                  dynamicFields.put(name, oldFieldPtsList + toAdd)
                case Some((ots1,ots2, omSet)) =>
                  val toAdd = (ots1,ots2,omSet + appMethod)
                  dynamicFields.put(name,oldFieldPtsList + toAdd)
              }
              false
            case _ => false
          }, emptyOk = true)
      case exclude =>
        ir.findInMethod(exclude.classType, exclude.simpleName, {
          case AssignCmd(StaticFieldReference(declaringClass, fieldName, _,_), source, loc) =>
            val sourcePts = ir.pointsToSet(exclude, source)
            val staticFieldsKey = (declaringClass, fieldName)
            val (oldStaticFieldsPt,oldSet) = excludeStaticFields.getOrElse(staticFieldsKey, (EmptyTypeSet, Set[MethodLoc]()))
            val newStaticFieldsPt = oldStaticFieldsPt.union(sourcePts)
            val newSet = oldSet + exclude
            excludeStaticFields.put(staticFieldsKey, (newStaticFieldsPt,newSet))
            false
          case AssignCmd(FieldReference(base, _, _, name), tgt, _) =>
            //if(name == "media" || name == "callback"){
            //println()
            //}
            val basePts = ir.pointsToSet(exclude, base)
            val tgtPts = ir.pointsToSet(exclude, tgt)
            val oldFieldPtsList = excludeDynamicFields.getOrElse(name, Set.empty)
            val applicable = oldFieldPtsList.find { case (pt1, pt2, _) => pt1.contains(basePts) && pt2.contains(tgtPts)}
            applicable match{
              case None =>
                val toAdd: (TypeSet, TypeSet, Set[MethodLoc]) = (basePts, tgtPts, Set(exclude))
                excludeDynamicFields.put(name, oldFieldPtsList + toAdd)
              case Some((ots1,ots2, omSet)) =>
                val toAdd = (ots1,ots2,omSet + exclude)
                excludeDynamicFields.put(name, oldFieldPtsList + toAdd)
            }
            false
          case _ => false
        }, emptyOk = true)
        println(s"ecluded method: ${exclude}")
    }
    FieldsLookup(staticFields.toMap, dynamicFields.toMap, excludeStaticFields.toMap, excludeDynamicFields.toMap,
      constStaticFields.toMap)
  }
  val fieldsLookupCache = mutable.Map[IRWrapper[M,C], FieldsLookup]()
  def fieldsLookup(ir:IRWrapper[M,C]): FieldsLookup = {
    if(!fieldsLookupCache.contains(ir)){
      val fl = computeFieldsLookup(ir)
      fieldsLookupCache.put(ir,fl)
    }
    fieldsLookupCache(ir)
  }

  case class FieldsLookup(staticFields:Map[(String,String), (TypeSet, Set[MethodLoc])],
                          dynamicFields:Map[String,Set[(TypeSet,TypeSet, Set[MethodLoc])]],
                          excludedStaticFeilds:Map[(String,String), (TypeSet, Set[MethodLoc])],
                          excludedDynamicFields:Map[String,Set[(TypeSet,TypeSet, Set[MethodLoc])]],
                         constStaticFields:Map[(String,String), PureVal]
                         )
  def staticFieldConstValue(ir:IRWrapper[M,C], field:StaticFieldReference):Option[PureVal] ={
    val lookup = fieldsLookup(ir)
    lookup.constStaticFields.get((field.declaringClass, field.fieldName))
  }
  def cellMayBeWritten(ir:IRWrapper[M,C],edge: HeapPtEdge, state:State):Boolean = edge match{
    case FieldPtEdge(base, fieldName) =>
      val baseTypeSet = state.sf.typeConstraints.getOrElse(base, TopTypeSet)
      fieldsLookup(ir).dynamicFields.get(fieldName).exists{writes =>
        writes.exists{case (ts1, _,_) => baseTypeSet.intersectNonEmpty(ts1)}}
    case StaticPtEdge(clazz, name) =>
      fieldsLookup(ir).staticFields.contains((clazz,name))
  }
  val PRINT_WRITELOC_OUTSIDE_FILTER = false
  def fieldMayNotBeWritten(ir:IRWrapper[M,C], field: (HeapPtEdge, PureExpr), state:State):Boolean = field match {
//    case (cell@FieldPtEdge(base, fieldName), NullVal) =>
//      !cellMayBeWritten(ir, cell, state)
//    case (cell@StaticPtEdge(clazz, name), NullVal) =>
//      !cellMayBeWritten(ir,cell,state)
    case (FieldPtEdge(base, fieldName), tgt: PureVar) =>
      val baseTypeSet = state.sf.typeConstraints.getOrElse(base, TopTypeSet)
      val tgtTypeSet = state.sf.typeConstraints.getOrElse(tgt, TopTypeSet)
      val lookup = fieldsLookup(ir)
      val notWritten = !lookup.dynamicFields.getOrElse(fieldName, Set()).exists { case (otherBase, otherTgt,_) =>
        baseTypeSet.intersectNonEmpty(otherBase) && tgtTypeSet.intersectNonEmpty(otherTgt)
      }
      if(PRINT_WRITELOC_OUTSIDE_FILTER  && notWritten){
        val found = lookup.excludedDynamicFields.getOrElse(fieldName,Set()).find{ case  (otherBase, otherTgt, methods) =>
          baseTypeSet.intersectNonEmpty(otherBase) && tgtTypeSet.intersectNonEmpty(otherTgt)
        }
        if(found.nonEmpty)
          println(s"AppCodeResolver: Field ${field} -> ${tgt} not written but found write in excluded method: ${found.get}")
      }
      notWritten
    case (StaticPtEdge(clazz, name), tgt: PureVar) =>
      val tgtTypes = state.sf.typeConstraints.getOrElse(tgt, TopTypeSet)
      val lookup = fieldsLookup(ir)
      val intersected = lookup.staticFields.getOrElse((clazz, name), (TopTypeSet, None))._1.intersect(tgtTypes)
      val notWritten = intersected.isEmpty
      if(PRINT_WRITELOC_OUTSIDE_FILTER  && notWritten){
        val found = lookup.excludedStaticFeilds.getOrElse((clazz, name), (EmptyTypeSet, Set[MethodLoc]()))
        if(found._1.intersectNonEmpty(tgtTypes))
          println(s"AppCodeResolver: Field ${field} -> ${tgt} not written but found write in excluded methods: ${found._2.take(1)}")
      }
      notWritten
    case _ => false
  }
}
/**
 *
 * @param ir
 * @param component limit analysis to packages matching expressions in here, use ! to say "not this component"
 * @tparam M
 * @tparam C
 */
class AppCodeResolver[M,C] (ir: IRWrapper[M,C]) {

  protected val excludedClasses = "dummyMainClass".r


  var appMethods: Set[MethodLoc] = ir.getAllMethods.filter(m => !isFrameworkClass(m.classType)).toSet
  private def iGetCallbacks():Set[MethodLoc] =
    appMethods.filter(resolveCallbackEntry(_).isDefined)
  private var callbacks:Set[MethodLoc] = null
  def getCallbacks:Set[MethodLoc] = {
    iGetCallbacks() // Some kind of race condition here prevents finding all the callbacks, call twice in the hopes that its better
    if(callbacks == null) {
      callbacks = iGetCallbacks()
    }
    callbacks
  }

  def invalidateCallbacks() = {
    appMethods = ir.getAllMethods.filter(m => !isFrameworkClass(m.classType)).toSet
    callbacks = null //iGetCallbacks()
  }

  def heuristicCiFlowsToDeref[M,C](messages:Set[OAbsMsg], filter:Option[String],
                                            abs:AbstractInterpreter[M,C]):Set[InitialQuery] = {

    val swappedMessages = messages.flatMap{
      case OAbsMsg(CIExit, signatures, lsVars,_) => Some(OAbsMsg(CIEnter, signatures, lsVars))
      case _ => None
    }
    val callinTargets = findCallinsAndCallbacks(swappedMessages,filter)
    val derefLocs = callinTargets.filter{ case (loc,_) =>
      ir.cmdAtLocation(loc).isInstanceOf[AssignCmd]
    }.flatMap{
      case (loc, _) => findFirstDerefFor(loc.method, loc, abs.w)
    }
    derefLocs.map{loc =>
      ReceiverNonNull(loc.method.getSignature, loc.line.lineNumber, derefNameOf(loc,ir))
    }
  }

  private def filtereAppMethods(filter: Option[String]): Set[MethodLoc] = {
    appMethods.filter {
      methodLoc: MethodLoc => // apply package filter if it exists
        filter.forall(methodLoc.classType.startsWith)
    }
  }

  private def methodIsMatchingCb(matchers:List[OAbsMsg], method:MethodLoc)
                                (implicit ch:ClassHierarchyConstraints):Boolean = {
    resolveCallbackEntry(method) match {
      case Some(CallbackMethodInvoke(sig, _)) =>
        matchers.exists(m => m.contains(CBEnter, sig) || m.contains(CBExit,sig))
      case Some(CallbackMethodReturn(sig, _, _)) =>
        matchers.exists(m => m.contains(CBExit,sig) || m.contains(CBEnter,sig))
      case None => false
    }
  }

  def heuristicDerefNullFinish[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[InitialQuery] = {
    // look for finish use somewhere in the app
    val filteredAppMethods: Set[MethodLoc] = filtereAppMethods(filter)
    val cfRes = abs.controlFlowResolver
    implicit val ch = abs.getClassHierarchy
    val finishExists = filteredAppMethods.exists{ m =>
      abs.controlFlowResolver.filterResolver.directCallsGraph(abs.w,m).exists {
        case CallinMethodReturn(sig) => SpecSignatures.Activity_finish.matches(sig)
        case CallinMethodInvoke(sig) => SpecSignatures.Activity_finish.matches(sig)
        case GroupedCallinMethodInvoke(_, _) =>throw new IllegalStateException("should not group here")
        case GroupedCallinMethodReturn(_, _) =>throw new IllegalStateException("should not group here")
        case _ => false
      }
    }
    if (!finishExists)
      return Set.empty
    val finishSensitiveCbs = List(ViewSpec.onClickI, ViewSpec.onMenuItemClickI)
    heuristicDerefNull(filter, abs, method =>
      methodIsMatchingCb(finishSensitiveCbs, method))
  }

  def heuristicDerefNullSynch[M, C](filter: Option[String], abs: AbstractInterpreter[M, C]): Set[InitialQuery] = {
    implicit val ch = abs.getClassHierarchy
    val syncCallbacks = List(SpecSignatures.RxJava_call_entry, SAsyncTask.postExecuteI,
      SJavaThreading.runnableI, SJavaThreading.callableI)
    heuristicDerefNull(filter, abs, method => methodIsMatchingCb(syncCallbacks, method))
  }

  def heuristicDerefNull[M,C](filter:Option[String], abs:AbstractInterpreter[M,C],
                              acceptMethod:MethodLoc => Boolean):Set[InitialQuery] = {
    //TODO: split this into deref in onClick and other ui callbacks where finish is invoked and deref in synchronization cb like onPostExecute or Action1

    val filteredAppMethods: Set[MethodLoc] = filtereAppMethods(filter)

    // find all fields set to null (and points to sets for dynamic fields)
    def findNullAssign(v:AppLoc):Option[(LVal, TypeSet)] = {
      BounderUtil.cmdAtLocationNopIfUnknown(v, ir) match {
        case AssignCmd(f: FieldReference, NullConst, _) => Some((f, ir.pointsToSet(v.method, f.base)))
        case AssignCmd(f: StaticFieldReference, NullConst, _) => Some((f, TopTypeSet))
        case _ => None
      }
    }
    val fields = filteredAppMethods.flatMap { m =>
      if(m.simpleName.startsWith("void <init>")) //Ignore fields set to null in initializers
        None
      else
        ir.allMethodLocations(m).flatMap ( findNullAssign )
    }

    // group field references by name
    val fieldNames: Map[String, Set[(LVal, TypeSet)]] = fields.groupBy{
      case (f:FieldReference, _) => f.name
      case (f:StaticFieldReference, _) => f.fieldName
      case _ => "----------------------"
    }

    // determine if a command within a method may alias one of the fields found earlier
    def matchesField(m:MethodLoc, cmd:CmdWrapper):Boolean = cmd match {
      case AssignCmd(_:LocalWrapper, FieldReference(base, _, _, name), _) =>
        val basePts = ir.pointsToSet(m, base)
        fieldNames.getOrElse(name, Set.empty).exists{
          case (f:FieldReference, pts) if f.name == name => basePts.intersectNonEmpty(pts)
          case _ => false
        }
      case AssignCmd(_:LocalWrapper, StaticFieldReference(declaringClass,name,_,_),_) =>
        fieldNames.getOrElse(name, Set.empty).exists{
          case (StaticFieldReference(fDeclaringClass, fName, _,_), _) =>
            fName == name && declaringClass == fDeclaringClass
          case _ => false
        }
      case _ => false
    }

    // Use simple scan through methods to find possible usages of the fields
    def findFieldMayBeAssignedTo(m:MethodLoc):Set[AppLoc] = {
      ir.allMethodLocations(m).filter{(loc:AppLoc) =>
        matchesField(m,ir.cmdAtLocation(loc))
      }
    }

    val acceptedAppMethods: Set[MethodLoc] = filteredAppMethods.filter(acceptMethod)
    acceptedAppMethods.flatMap{m =>
      val fieldUse = findFieldMayBeAssignedTo(m)
      val res = fieldUse.flatMap(findFirstDerefFor(m,_, ir))
      res.map{loc =>
        ReceiverNonNull(loc.method.getSignature, loc.line.lineNumber,derefNameOf(loc,ir))}
    }.toSet
  }

  /**
   * Search for syntactic locations in callbacks that may crash if a field is null at the entry of the callback
   * @param filter packages to include in the search
   * @param abs abstract interpreter class
   * @return queries for dereferences that may crash
   */
  def derefFromField[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[Qry] = {
    val derefToFilter = allDeref(filter, abs)
    def callbackEntryWithNullField(qry:Qry):Boolean = {
      qry.state.callStack.isEmpty && {
        qry.state.inlineConstEq().exists{ reducedState =>
          reducedState.sf.heapConstraints.exists{
            case (_:FieldPtEdge, NullVal) => true
            case _ => false
          }
        }
      }

    }

    derefToFilter.filter { q =>
      val res = abs.run(DirectInitialQuery(q),
        stopExplorationAt = Some(q2 => if (q2.state.callStack.isEmpty) true else callbackEntryWithNullField(q2)))
      val resTerminals = res.flatMap(_.terminals)
      resTerminals.exists(node => callbackEntryWithNullField(node.qry))
    }
  }

  /**
   * Search for syntactic locations where the return from specific callins is dereferenced
   * @param callins that may return null
   * @param filter packages to include in search
   * @param abs abstract interpreter class
   * @return query locations that may crash if callin returns null
   */
  def derefFromCallin[M,C](callins: Set[OAbsMsg], filter:Option[String],
                                    abs:AbstractInterpreter[M,C]):Set[InitialQuery] = {
    // Add callins to matcher space of spec so they show up in abstract states
    val absCallinAdded =
      abs.updateSpec(abs.getConfig.specSpace.copy(matcherSpace = abs.getConfig.specSpace.getMatcherSpace ++ callins))

    val signatures = callins.map(_.signatures)
    def nullValueFrom(q:Qry):Boolean = {
      // test if we have reached a message in our signature set that needs to return null
      q.state.inlineConstEq().exists { reducedState =>
       reducedState.sf.traceAbstraction.rightOfArrow.headOption.exists {
         case OAbsMsg(CIExit, sig, NullVal::_,_) =>
           signatures.contains(sig)
         case _ => false
       }
     }
    }
    // get all dereference locations
    val derefToFilter = allDeref(filter, absCallinAdded)

    // run goal directed analysis on each location
    // stop if callback entry reached (call stack empty) or message history has obligate null value from a callin
    val outQ = derefToFilter.filter{q =>
      val res = absCallinAdded.run(DirectInitialQuery(q),
        stopExplorationAt = Some(q2 => if(q2.state.callStack.isEmpty) true else nullValueFrom(q2)))
      val resTerminals = res.flatMap(_.terminals)
      resTerminals.exists(node => nullValueFrom(node.qry))
    }
    outQ.map{qry =>
      val appLoc = qry.loc.asInstanceOf[AppLoc]
      ReceiverNonNull(qry.loc.containingMethod.get.getSignature, appLoc.line.lineNumber,derefNameOf(appLoc, ir))}
  }

  def allDeref[M,C](filter:Option[String], abs:AbstractInterpreter[M,C]):Set[Qry] = {
    val appClasses = appMethods.map(m => m.classType)
    val filtered = appClasses.filter(c => filter.forall(c.startsWith))
    val initialQueries = filtered.map(c => AllReceiversNonNull(c))
    initialQueries.flatMap{q => q.make(abs)}
  }


  /**
   * TODO: not fully implemented
   * Compute null data flows within a single callback.
   * Warning: not a sound analysis, just best effort
   * @param sources Locations returning the values of interest
   * @param cfRes Control flow resolver
   * @return
   */
  def nullValueMayFlowTo[M,C](sources:Iterable[AppLoc],
                                         cfRes:ControlFlowResolver[M,C]):Set[AppLoc] = {

    //TODO: this is meant to be a interprocedural version of "findFirstDerefFor", only complete if needed
    def mk(v:Any):ValueSpot = v match{
      case LocalWrapper(name, _) => LocalValue(name)
    }
    sealed trait ValueSpot  //note extend further for fields arrays etc if soundness is desired later
    case class LocalValue(name:String) extends ValueSpot
    type AbsState = Map[ValueSpot,Boolean]
    val botVal:AbsState = Map.empty
    def isSensitive(loc:AppLoc, absState:AbsState):Boolean = {
      ir.cmdAtLocation(loc) match {
        case ReturnCmd(returnVar, loc) => false
        case AssignCmd(target, FieldReference(base,_,_,_), loc) =>
          absState.contains(mk(base))
        case AssignCmd(target, source, loc) => false
        case InvokeCmd(_:StaticInvoke, _) => false //TODO: capture @NonNull annotations
        case InvokeCmd(SpecialInvoke(base,_,_,_),_) =>
          absState.contains(mk(base))
        case InvokeCmd(VirtualInvoke(base,_,_,_),_) =>
          absState.contains(mk(base))
        case Goto(b, trueLoc, loc) => false
        case NopCmd(loc) => false
        case SwitchCmd(key, targets, loc) => false
        case ThrowCmd(loc) => false
      }
    }
    def mkInitFlow(loc:AppLoc):AbsState = BounderUtil.cmdAtLocationNopIfUnknown(loc,ir) match {
      case AssignCmd(tgt, _,_) => Map(mk(tgt) -> true)
      case _ => Map.empty
    }
    def transfer(absState:AbsState,loc:Loc):AbsState = loc match {
      case appLoc@AppLoc(method, line, true) =>
        val cmd = ir.cmdAtLocation(appLoc)
        ???
      case appLoc@AppLoc(_,_,false) => absState
      case InternalMethodReturn(clazz, name, loc) =>
        ???
      case InternalMethodInvoke(clazz, name, loc) =>
        ???
      case CallinMethodReturn(sig) =>
        absState //Note: if we wanted this to be sound, we would capture case where callin causes data flow
      case CallinMethodInvoke(sig) =>
        absState
      case GroupedCallinMethodInvoke(targetClasses, fmwName) => absState
      case GroupedCallinMethodReturn(targetClasses, fmwName) => absState
      case CallbackMethodInvoke(sig, loc) => botVal
      case CallbackMethodReturn(sig, loc, line) => botVal
      case SkippedInternalMethodInvoke(clazz, name, loc) => throw new IllegalArgumentException()
      case SkippedInternalMethodReturn(clazz, name, rel, loc) => throw new IllegalArgumentException()
    }

    def join(absState1: AbsState, absState2:AbsState):AbsState =
      absState1.foldLeft(absState2){
        case (acc,k->v) => acc + (k -> (v || acc.getOrElse(k,false)))
      }

    // accumulator BitSet is set of positions with data flow
    // position 0 is output (value assigned such as x in x = f.y, y is position 1 etc).
    // TODO: may be nice to have interproc version of this
    val fp: Map[Loc, AbsState] = BounderUtil.graphFixpoint[Loc, AbsState](
      start = sources.toSet,
      startVal = sources.flatMap(mkInitFlow).toMap,
      botVal = Map.empty,
      next = n => cfRes.resolveSuccessors(n,skipCallins = true),
      //          ir.commandPredecessors(n).map{v => BounderUtil.cmdAtLocationNopIfUnknown(v,ir).mkPre}.toSet
      comp = transfer,
      join = join
    )
    fp.flatMap{
      case (loc:AppLoc,absState) =>
        if(isSensitive(loc,absState)) Some(loc) else None
      case _ => None
    }.toSet

  }

  def matchesCI(i: Invoke, cbMsg:Iterable[OAbsMsg])(implicit ch:ClassHierarchyConstraints): Set[OAbsMsg] = {
    val sig = i.targetSignature
    cbMsg.filter { oMsg =>
      List(CIEnter, CIExit).exists { mt => oMsg.contains(mt, sig) }
    }.toSet
  }

  final def findCallinsAndCallbacks(messages:Set[OAbsMsg],
                                    packageFilter:Option[String]):Set[(AppLoc,OAbsMsg)] = {
    implicit val ch = ir.getClassHierarchyConstraints
    if(messages.exists{m => m.mt == CBEnter || m.mt == CBExit})
      ??? //TODO: unimplemented, add callback and callin search
    val cbMsg = messages.filter{m => m.mt == CIExit || m.mt == CIEnter}

    val filteredAppMethods = appMethods.filter {
      case methodLoc: MethodLoc => // apply package filter if it exists
        packageFilter.forall(methodLoc.classType.startsWith)
    }
    val invokeCmds = filteredAppMethods.flatMap{m =>
      ir.allMethodLocations(m).flatMap{v => BounderUtil.cmdAtLocationNopIfUnknown(v,ir) match{
        case AssignCmd(_, i: SpecialInvoke, _) => matchesCI(i,cbMsg).map((v, _))
        case AssignCmd(_, i: VirtualInvoke, _) => matchesCI(i,cbMsg).map((v, _))
        case InvokeCmd(i: SpecialInvoke, _) => matchesCI(i,cbMsg).map((v, _))
        case InvokeCmd(i: VirtualInvoke, _) => matchesCI(i,cbMsg).map((v, _))
        case _ => None
      }}
    }

//    val returns = filteredAppMethods.flatMap{m =>
//      ir.makeMethodRetuns(m).toSet.map((v: AppLoc) => BounderUtil.cmdAtLocationNopIfUnknown(v,ir).mkPre)}
//    val invokeCmds2 = BounderUtil.graphFixpoint[CmdWrapper, Set[(AppLoc,OAbsMsg)]](start = returns, Set(), Set(),
//      next = n => ir.commandPredecessors(n).map((v: AppLoc) =>
//        BounderUtil.cmdAtLocationNopIfUnknown(v, ir).mkPre).toSet,
//      comp = {
//        case (acc, v) =>
//          val newLocs: Set[(AppLoc,OAbsMsg)] = ir.commandPredecessors(v).flatMap { v =>
//            ir.cmdAtLocation(v) match {
//              case AssignCmd(_, i: SpecialInvoke, _) => matchesCI(i).map((v,_))
//              case AssignCmd(_, i: VirtualInvoke, _) => matchesCI(i).map((v,_))
//              case InvokeCmd(i: SpecialInvoke, _) => matchesCI(i).map((v,_))
//              case InvokeCmd(i: VirtualInvoke, _) => matchesCI(i).map((v,_))
//              case _ => None
//            }
//          }.toSet
//          acc ++ newLocs
//      },
//      join = (a, b) => a.union(b)
//    ).flatMap { case (_, v) => v }.toSet
    invokeCmds
  }
  @tailrec
  final def sampleDeref(packageFilter:Option[String]):AppLoc = {
    def keepI(i:Invoke):Boolean = i match {
      case VirtualInvoke(_, _, targetMethod, _) => !targetMethod.contains("<init>")
      case SpecialInvoke(_, _, targetMethod, _) => !targetMethod.contains("<init>")
      case StaticInvoke(_, targetMethod, _) =>  !targetMethod.contains("<init>")
    }
    val filteredAppMethods = appMethods.filter{
      case methodLoc: MethodLoc => // apply package filter if it exists
        packageFilter.forall(methodLoc.classType.startsWith)
    }.toArray
    val methodInd = Random.nextInt(filteredAppMethods.size)
    val m = filteredAppMethods(methodInd)
//    val randomMethodList = Random.shuffle(appMethods.filter{
//      case methodLoc: MethodLoc => // apply package filter if it exists
//        packageFilter.forall(methodLoc.classType.startsWith)
//    }.toList)
//    val m = randomMethodList.head

    // generate set of dereferences for method

    val returns = ir.makeMethodRetuns(m).toSet.map((v: AppLoc) => BounderUtil.cmdAtLocationNopIfUnknown(v,ir).mkPre)
    val derefs = BounderUtil.graphFixpoint[CmdWrapper, Set[AppLoc]](start = returns,Set(),Set(),
      next = n => ir.commandPredecessors(n).map((v: AppLoc) =>
        BounderUtil.cmdAtLocationNopIfUnknown(v,ir).mkPre).toSet,
      comp = {
        case (acc,v) =>
          val newLocs:Set[AppLoc] = ir.commandPredecessors(v).flatMap{v => ir.cmdAtLocation(v) match{
            case AssignCmd(_, i:SpecialInvoke, _) if keepI(i) => Some(v)
            case AssignCmd(_, i:VirtualInvoke, _) if keepI(i) => Some(v)
            case AssignCmd(_, i:FieldReference, _) => Some(v)
            case InvokeCmd(i:SpecialInvoke, _) if keepI(i) => Some(v)
            case InvokeCmd(i:VirtualInvoke,_) if keepI(i) => Some(v)
            case _ => None
          }}.toSet
          acc ++ newLocs
      },
      join = (a,b) => a.union(b)
    ).flatMap{ case (_,v) => v}.toSet.toList
    if(derefs.isEmpty){
      sampleDeref(packageFilter)
    }else {
      val shuf = Random.shuffle(derefs)
      val s = shuf.head
      if(s.line.lineNumber > 0)
        s
      else
        sampleDeref(packageFilter)
    }
  }

  var isFwkMemo:mutable.Map[String,Boolean] = null
  def isFrameworkClass(fullClassName:String):Boolean = {
//    if(fullClassName == "java.lang.Integer"){
//      return false
//    }
    if(isFwkMemo == null){ // initialize here because constructor isn't executed nicely
      isFwkMemo = mutable.Map[String,Boolean]()
    }
    isFwkMemo.getOrElse(fullClassName, {
      val computed = fullClassName match {
        case FrameworkExtensions.extensionRegex() =>
          true
        case _ =>
          false
      }
      isFwkMemo.put(fullClassName, computed)
      computed
    })
  }

  def isAppClass(fullClassName:String):Boolean = {
    if(isFrameworkClass(fullClassName))
      return false
    fullClassName match{
      case excludedClasses() =>
        false
      case _ => true
    }
  }


  def resolveCallLocation(tgt: UnresolvedMethodTarget): Set[Loc] = {
    tgt.loc.map{m =>
      val classType = m.classType
      if(isFrameworkClass(classType)){
        CallinMethodReturn(Signature(classType, m.simpleName))
      }else {
        InternalMethodReturn(classType, m.simpleName, m)
      }
    }
  }

  private val CLINIT = "void <clinit>()"
  def resolveCallbackExit(method: MethodLoc, retCmdLoc: Option[LineLoc]): Option[Loc] = {
//    if(method.simpleName == CLINIT){
//      return Some(CallbackMethodReturn(method.classType, CLINIT,method, retCmdLoc))
//    }
    val overrides = ir.getOverrideChain(method)
    if(overrides.size == 1 && overrides.last.classType == "java.lang.Object" && overrides.last.simpleName == "<init>"){
      // Object init is not considered a callback
      return None
    }
    if (overrides.size > 0) {
      val leastPrecise: MethodLoc = overrides.last
      Some(CallbackMethodReturn(Signature(method.classType, leastPrecise.simpleName), method, retCmdLoc))
    } else None

  }
  def resolveCallbackEntry(method:MethodLoc):Option[Loc] = {
//    if(method.simpleName == "void <clinit>()"){
//      // <clinit> considered a callback
//      return Some(CallbackMethodInvoke("java.lang.Object", "void <clinit>()", method))
//    }
    if(method.isInterface) {
      return None // Interface methods cannot be callbacks
    }
    val chain = ir.getOverrideChain(method)
    val overrides = chain.filter(c =>
      isFrameworkClass(
        SootWrapper.stringNameOfClass(
          c.asInstanceOf[JimpleMethodLoc].method.getDeclaringClass)))
    if(overrides.size == 1 && overrides.last.classType == "java.lang.Object" && overrides.last.simpleName == "<init>"){
      // Object init is not considered a callback unless it overrides a subclass's init
      return None
    }
    if (overrides.size > 0) {
      val leastPrecise: MethodLoc = overrides.last
//      Some(CallbackMethodInvoke(leastPrecise.classType, leastPrecise.simpleName, method))
      Some(CallbackMethodInvoke(Signature(method.classType, leastPrecise.simpleName), method))
    } else None
  }
}