package edu.colorado.plv.bounder.symbolicexecutor

import better.files.Resource
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.ir._
import edu.colorado.plv.bounder.lifestate.LifeState._
import edu.colorado.plv.bounder.lifestate.{LifeState, SpecSpace}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.TransferFunctions.{inVarsForCall, nonNullCallins, relevantAliases}
import edu.colorado.plv.bounder.symbolicexecutor.state._
import upickle.default._
import edu.colorado.plv.bounder.symbolicexecutor.state.PrettyPrinting

object TransferFunctions{
  private val nonNullDefUrl = List(
    "NonNullReturnCallins.txt",
    "NonNullReturnCallins",
    "/resources/NonNullReturnCallins.txt",
    "resources/NonNullReturnCallins.txt",
  )
  lazy val nonNullCallins: Seq[AbsMsg] = {
    val mp = nonNullDefUrl.flatMap{ (path:String) =>
      //    val source = Source.fromFile(frameworkExtPath)
      try {
        val source = Resource.getAsString(path)
        Some(LifeState.parseIFile(source))
      }catch{
        case t:IllegalArgumentException => throw t //exception thrown when parsing fails
        case e:NoSuchElementException if e.getMessage.contains("Could not find resource") =>
          None // deal with instability of java jar file resource resolution
      }
    }
    if(mp.isEmpty){
      throw new IllegalStateException("Scala resource resolution is strange, try rebuilding with 'sbt assembly'?")
    }
    mp.head
  }

  // transfer should simply define any variables that aren't seen in the state but read
  // alias considerations are done later by the trace abstraction or by separation logic
  def relevantAliases(pre:State,
                      dir:MessageType,
                      signature: Signature,
                      specSpace: SpecSpace,
                      lst : List[Option[RVal]])(implicit ch:ClassHierarchyConstraints):List[Option[RVal]] = {
    //TODO: should use pre to determine which vars should be materialized
    val relevantI = specSpace.findIFromCurrent(dir,signature,Some(lst))
    lst.zipWithIndex.map{ case (rval,ind) =>
      val existsNAtInd = relevantI.exists{i =>
        val vars: Seq[PureExpr] = i.lsVars
        val out = (ind < vars.length) && vars(ind) != TopVal
        out
      }
      if(existsNAtInd) rval else None
    }
  }
  private def inVarsForCall(i:Invoke):List[Option[RVal]] = i match{
    case i@VirtualInvoke(tgt, _, _, _) =>
      Some(tgt) :: i.params.map(Some(_))
    case i@SpecialInvoke(tgt, _, _, _) =>
      Some(tgt) :: i.params.map(Some(_))
    case i@StaticInvoke(_, _, _) =>
      None :: i.params.map(Some(_))
  }
  def inVarsForCall[M,C](source: AppLoc, w:IRWrapper[M,C]):List[Option[RVal]] = {
    w.cmdAtLocation(source) match {
      case AssignCmd(local : LocalWrapper, i:Invoke, _) =>
        Some(local) :: inVarsForCall(i)
      case InvokeCmd(i: Invoke, _) =>
        None :: inVarsForCall(i)
      case v =>
        //Note: jimple should restrict so that assign only occurs to locals from invoke
        throw new IllegalStateException(s"$v is not a call to a method")
    }
  }
}

class TransferFunctions[M,C](w:IRWrapper[M,C], specSpace: SpecSpace,
                             classHierarchyConstraints: ClassHierarchyConstraints, canWeaken:Boolean,
                             filterResolver: FilterResolver[M,C]) {
  private implicit val ch = classHierarchyConstraints
  private implicit val irWrapper = w
  def defineVarsAs(state: State, comb: List[(Option[RVal], Option[PureExpr])]):State =
    comb.foldLeft(state){
      case (stateNext, (None,_)) => stateNext
      case (stateNext, (_,None)) => stateNext
      case (stateNext, (Some(rval), Some(pexp))) => stateNext.defineAs(rval, pexp)
    }

  /**
   *
   * @param postState state after current location in control flow
   * @param target predecessor of current location
   * @param source current location
   * @return set of states that may reach the target state by stepping from source to target
   */
  def transfer(postState: State, target: Loc, source: Loc): Set[State] = (source, target) match {
    case (source@AppLoc(m, _, false), cmret:CallinMethodReturn) =>
      val g = GroupedCallinMethodReturn(Set(cmret.sig.base), cmret.sig.methodSignature)
      transfer(postState,g,source)
    case (source@AppLoc(m, _, false), cmret@GroupedCallinMethodReturn(_, _)) =>
      // traverse back over the retun of a callin

      // if post state has materialized value for receiver, assume non-null
      val cmd = w.cmdAtLocation(source)
      val inv = cmd match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c => throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[LocalWrapper] = inv match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }
      val postState2 = if(receiverOption.exists{postState.containsLocal}) {
        val localV = postState.get(receiverOption.get).get
        postState.addPureConstraint(PureConstraint(localV, NotEquals, NullVal))
      } else postState

      val sig = msgCmdToMsg(cmret)
      val inVars: List[Option[RVal]] = inVarsForCall(source,w)
      val relAliases = relevantAliases(postState2, CIExit, sig,specSpace,inVars)
      val frame = CallStackFrame(target, Some(source.copy(isPre = true)), Map())
      val (_, state0) = getOrDefineRVals(m,relAliases, postState2)
      val outState = newMsgTransfer(source.method, CIExit, sig, inVars, state0)
      val outState1: Set[State] = inVars match{
        case Some(retVar:LocalWrapper)::_ =>
          // If the current callin is in NonNullReturnCallins.txt constrain the return value to be non-null
          if (nonNullCallins.exists(i => i.contains(CIExit, sig)))
            // if non-null return callins defines this method, assume that the materialized return value is non null
            outState.map{s =>
              if(s.containsLocal(retVar))
                s.addPureConstraint(PureConstraint(s.get(retVar).get, NotEquals, NullVal))
              else s
            }
          else outState
        case _ => outState
      }
      val outState2 = outState1.map(s2 => s2.copy(sf = s2.sf.copy(callStack = frame::s2.callStack),
        nextCmd = List(target),
        alternateCmd = Nil))
      val out = outState2.map{ oState =>
        //clear assigned var from stack if exists
        val statesWithClearedReturn = inVars.head match{
          case Some(v:LocalWrapper) => {
            val newStack = oState.sf.callStack match {
              case h :: (fr:MaterializedCallStackFrame) :: t =>
                if(!inVars.tail.contains(Some(v))) {
                  h :: fr.removeStackVar(StackVar(v.name)) :: t
                }else h::fr::t
              case v => v
            }
            //oState.clearLVal(v)
            oState.copy(sf = oState.sf.copy(callStack = newStack))
          }
          case None => oState
          case v => throw new IllegalStateException(s"Malformed IR. Callin result assigned to non-local: $v")
        }
        statesWithClearedReturn
      }
      out
    case (cminv@GroupedCallinMethodInvoke(targets, _), tgt@AppLoc(m,_,true)) =>
      assert(postState.callStack.nonEmpty, "Bad control flow, abstract stack must be non-empty.")
      val invars = inVarsForCall(tgt,w)
      val sig = msgCmdToMsg(cminv)
      val relAliases = relevantAliases(postState, CIEnter, sig,specSpace,invars)
      val ostates:Set[State] = {
        val (rvals, state0) = getOrDefineRVals(m,relAliases, postState)
        Set(state0)
      }
      //Only add receiver if this or callin return is in abstract trace
      val traceNeedRec = List(CIEnter, CIExit).exists( dir => specSpace.findIFromCurrent(dir, sig)
        .nonEmpty)
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))
      //TODO: why does onDestroy exit have a bunch of alternate locations of pre-line: -1 r0:= @this:MyActivity$1/2...
      ostates.map{s =>
        // Pop stack and set command just processed
        val s2 = s.copy(sf = s.sf.copy(callStack = s.callStack.tail))
        // If dynamic invoke, restrict receiver type by the callin we just came from
        invars match{
          case _::Some(rec)::_ if traceNeedRec || cfNeedRec =>
            val (recV,stateWithRec) = s2.getOrDefine(rec, Some(tgt.method))
//            val pureFormulaConstrainingReceiver = stateWithRec.pureFormula +
//              PureConstraint(recV, NotEquals, NullVal)
            stateWithRec.addPureConstraint(PureConstraint(recV, NotEquals, NullVal))
              .constrainOneOfType(recV, targets.flatMap(ch.getSubtypesOf), ch)
          case _ => s2
        }
      }.map(_.copy(nextCmd = List(target), alternateCmd = Nil))

    case (GroupedCallinMethodReturn(_,_), GroupedCallinMethodInvoke(_,_)) =>
      Set(postState).map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (_:CallinMethodReturn, _:CallinMethodInvoke) =>
      Set(postState).map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (cminv@CallinMethodInvoke(sig), tgt@AppLoc(m, _, true)) =>
      assert(postState.callStack.nonEmpty, "Bad control flow, abstract stack must be non-empty.")
      val invars = inVarsForCall(tgt,w)
      val sig = msgCmdToMsg(cminv)
      val relAliases = relevantAliases(postState, CIEnter, sig,specSpace,invars)
      val ostates:Set[State] = {
        val (rvals, state0) = getOrDefineRVals(m,relAliases, postState)
//        val state1 = traceAllPredTransfer(CIEnter, (pkg, name), rvals, state0)
        Set(state0)
      }
      //Only add receiver if this or callin return is in abstract trace
      val traceNeedRec = List(CIEnter, CIExit).exists( dir => specSpace.findIFromCurrent(dir, sig)
        .nonEmpty)
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))

      ostates.map{s =>
        // Pop stack and set command just processed
        val s2 = s.copy(sf = s.sf.copy(callStack = s.callStack.tail), nextCmd = List(tgt))
        // If dynamic invoke, restrict receiver type by the callin we just came from
        val out = invars match{
          case _::Some(rec)::_ if traceNeedRec || cfNeedRec =>
            val (recV,stateWithRec) = s2.getOrDefine(rec,Some(tgt.method))
            stateWithRec.addPureConstraint(PureConstraint(recV, NotEquals, NullVal))
              .constrainUpperType(recV, sig.base, ch)
          case _ =>
            s2
        }
        out
      }.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (src@AppLoc(_, _, true), AppLoc(_, _, false)) =>
      Set(postState.setNextCmd(List(src)))
    case (appLoc@AppLoc(c1, m1, false), postLoc@AppLoc(c2, m2, true)) if c1 == c2 && m1 == m2 =>
      cmdTransfer(w.cmdAtLocation(appLoc), postState).map(_.setNextCmd(List(postLoc))).map(_.copy(alternateCmd = Nil))
    case (AppLoc(containingMethod, _, true), cmInv@CallbackMethodInvoke(sig1, l1)) =>
      // If call doesn't match return on stack, return bottom
      // Target loc of CallbackMethodInvoke means just before callback is invoked
      if(postState.callStack.nonEmpty){
        postState.callStack.head match {
          case CallStackFrame(CallbackMethodReturn(sig2,l2,_),_,_) if sig1 != sig2 || l1 != l2 =>
            throw new IllegalStateException("ControlFlowResolver should enforce stack matching")
          case _ =>
        }
      }

      val invars: List[Option[LocalWrapper]] = None :: containingMethod.getArgs
      val sig = msgCmdToMsg(cmInv)
      val relAliases = relevantAliases(postState, CBEnter, sig,specSpace,invars)
      val (_, state0) = getOrDefineRVals(containingMethod, relAliases,postState)
      val b = newMsgTransfer(containingMethod, CBEnter, sig, invars, state0)

      // pair state with this local before stack pop
      val thisStatePair: Set[(Option[PureExpr], State)] = b.map{ s =>
        val localThis: Option[PureExpr] = irWrapper.getThisVar(containingMethod)
          .flatMap(tv => s.get(tv)(irWrapper))
        val atThis = s.get(LocalWrapper("@this","_"))
        (localThis, atThis) match{
          case (Some(v1), Some(v2)) =>
            assert(v1 == v2)
            (Some(v1),s)
          case (Some(v), None) => (Some(v),s)
          case (None,Some(v)) => (Some(v),s)
          case (None,None) => (None,s)
        }
      }

      // Pop the call string
      val statePopped = thisStatePair.map{
        case (thisV,s) =>
          val newS = s.copy(sf = s.sf.copy(
            callStack = if(s.callStack.isEmpty) Nil else s.callStack.tail),
            nextCmd = List(target),
            alternateCmd = Nil,
            currentCallback = None
          )
          (thisV,newS)
      }
      assert(statePopped.forall{s => s._2.sf.callStack.isEmpty}, "call stack should be empty when reaching cb")

      // If processing the entry of <init> as a callback,
      //   add constraint that nothing in the trace before now can reference current value
      //   Filter state if a heap cell references v and does not point to null or if a var points to new value
      // If processing the entry of <clinit> as a callback,
      //
      statePopped.flatMap {
        case (Some(thisV:PureVar),s) if sig1.methodSignature.contains("void <init>(") =>
          addRefCreateToState(s,thisV)
        case (None, s) if sig1.methodSignature.contains("void <clinit>()") =>
          val newTrAbs = s.traceAbstraction.copy(rightOfArrow = CLInit(sig1.base)::s.traceAbstraction.rightOfArrow)
          Some(s.copy(sf = s.sf.copy(traceAbstraction = newTrAbs)))
        case (Some(_),s) => Some(s)
        case (None,s) => Some(s)
        case _ => throw new IllegalStateException("""Non-pure var as "this" local.""")
      }


    case (CallbackMethodInvoke(tgtSig, _), targetLoc@CallbackMethodReturn(_,mloc, _)) =>
      // Cannot jump back to callback on a class that will have it's static initializer called in the future
      if(futureCLInit(postState,tgtSig.base))
        return Set()
      // Case where execution goes to the exit of another callback
      // TODO: nested callbacks not supported yet, assuming we can't go back to callin entry
      // TODO: note that the callback method invoke is to be ignored here.
      // Control flow resolver is responsible for the
      val appLoc = AppLoc(targetLoc.loc, targetLoc.line.get,isPre = false)
      val rvar = w.cmdAtLocation(appLoc) match{
        case ReturnCmd(v,_) =>v
        case c => throw new IllegalStateException(s"return from non return command $c ")
      }
      val newFrame = CallStackFrame(targetLoc, None, Map())
      val sig = msgCmdToMsg(target)
      // Push frame regardless of relevance
      val pre_push = postState.copy(sf = postState.sf.copy(callStack = newFrame::postState.callStack))
      val localVarOrVal: List[Option[RVal]] = rvar::mloc.getArgs
      val relAliases = relevantAliases(postState, CBExit, sig,specSpace,localVarOrVal)
      val state1 = newMsgTransfer(mloc, CBExit, sig, relAliases, pre_push)
      state1.map(_.copy(nextCmd = List(target), alternateCmd = Nil, currentCallback = Some(targetLoc)))
    case (CallbackMethodReturn(_,mloc1,_), AppLoc(mloc2,_,false)) =>
      assert(mloc1 == mloc2)
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (InternalMethodInvoke(invokeType, _,_), al@AppLoc(_, _, true)) =>
      val cmd = w.cmdAtLocation(al) match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c =>
          throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[RVal] = cmd match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }
      val argOptions: List[Option[RVal]] = cmd.params.map(Some(_))
      val state0 = postState.setNextCmd(List(source))

      // Always define receiver to reduce dynamic dispatch imprecision
      // Value is discarded if static call
      //TODO: check if skipped internal method and don't materialize receiver or other args
      //TODO: implemented this, check that it works
      val cfNeedRec = postState.alternateCmd.exists(other => !postState.nextCmd.contains(other))
      val (receiverValue,stateWithRec) = if(receiverOption.isEmpty){
        (None,state0)
      }else if(cfNeedRec){
        val (recV,st) = state0.getOrDefine(LocalWrapper("@this","_"), source.containingMethod)
        (Some(recV),st)
      }else{
        (state0.get(LocalWrapper("@this","_")), state0)
      }
      val stateWithRecPf = stateWithRec.copy(sf = stateWithRec.sf.copy(pureFormula = stateWithRec.pureFormula ++
//        PureConstraint(receiverValue, TypeComp, SubclassOf(invokeType)) +
        receiverValue.map(PureConstraint(_, NotEquals, NullVal))
      ))
      val stateWithRecTypeCst = if(receiverValue.isDefined)
        stateWithRecPf.constrainUpperType(receiverValue.get.asInstanceOf[PureVar], invokeType,ch)
      else stateWithRecPf

      // Get values associated with arguments in state
      val frameArgVals: List[Option[PureExpr]] =
        cmd.params.indices.map(i => stateWithRecTypeCst.get(LocalWrapper(s"@parameter$i", "_"))).toList

      // Combine args and params into list of tuples
      val allArgs = receiverOption :: argOptions
      val allParams: Seq[Option[PureExpr]] = (receiverValue::frameArgVals)
      val argsAndVals: List[(Option[RVal], Option[PureExpr])] = allArgs zip allParams

      // Possible stack frames for source of call being a callback or internal method call
      val out = if (stateWithRecTypeCst.callStack.size == 1) {
        val newStackFrames: List[CallStackFrame] =
          BounderUtil.resolveMethodReturnForAppLoc(w.getAppCodeResolver, al).map(mr => CallStackFrame(mr, None, Map()))
        val newStacks = newStackFrames.map{frame =>
          frame :: (if (stateWithRecTypeCst.callStack.isEmpty) Nil else stateWithRecTypeCst.callStack.tail)}
        val nextStates = newStacks.map(newStack =>
          stateWithRecTypeCst.copy(sf = stateWithRecTypeCst.sf.copy(callStack = newStack)))
        nextStates.map(nextState => defineVarsAs(nextState, argsAndVals)).toSet
      }else if (stateWithRecTypeCst.callStack.size > 1){
        val state1 = stateWithRecTypeCst
          .copy(sf = stateWithRecTypeCst.sf.copy(callStack = stateWithRecTypeCst.callStack.tail))
        Set(defineVarsAs(state1, argsAndVals))
      }else{
        throw new IllegalStateException("Abstract state should always have a " +
          "stack when returning from internal method.")
      }
      out.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (SkippedInternalMethodInvoke(invokeType, _,_), al@AppLoc(_, _, true)) =>
      // Possible stack frames for source of call being a callback or internal method call
      val out = if (postState.callStack.size == 1) {
        val newStackFrames: List[CallStackFrame] =
          BounderUtil.resolveMethodReturnForAppLoc(w.getAppCodeResolver, al).map(mr => CallStackFrame(mr, None, Map()))
        val newStacks = newStackFrames.map { frame =>
          frame :: (if (postState.callStack.isEmpty) Nil else postState.callStack.tail)
        }
        val nextStates = newStacks.map(newStack => postState.setCallStack(newStack))
//          nextStates.map(nextState => defineVarsAs(nextState, argsAndVals)).toSet
        nextStates.toSet
      } else if (postState.callStack.size > 1) {
        val state1 = postState.setCallStack(postState.callStack.tail)
        Set(state1)
      } else {
        throw new IllegalStateException("Abstract state should always have a " +
          "stack when returning from internal method.")
      }
      out.map(_.copy(nextCmd = List(target), alternateCmd = Nil))
    case (retloc@AppLoc(mloc, line, false), mRet: InternalMethodReturn) =>
      val cmd = w.cmdAtLocation(retloc)
      val retVal:Map[StackVar, PureExpr] = cmd match{
        case AssignCmd(tgt, _:Invoke, _) =>
          postState.get(tgt).map(v => Map(StackVar("@ret") -> v)).getOrElse(Map())
        case InvokeCmd(_,_) => Map()
        case _ => throw new IllegalStateException(s"malformed bytecode, source: $retloc  target: $mRet")
      }
      val inv = cmd match{
        case InvokeCmd(inv : Invoke, _) => inv
        case AssignCmd(_, inv: Invoke, _) => inv
        case c => throw new IllegalStateException(s"Malformed invoke command $c")
      }
      val receiverOption: Option[LocalWrapper] = inv match{
        case v:VirtualInvoke => Some(v.target)
        case s:SpecialInvoke => Some(s.target)
        case _:StaticInvoke => None
      }

      val postState2 = if(receiverOption.exists{postState.containsLocal}) {
        val localV = postState.get(receiverOption.get).get
//        postState.copy(pureFormula = postState.pureFormula + PureConstraint(localV, NotEquals, NullVal))
        postState.addPureConstraint(PureConstraint(localV, NotEquals, NullVal))
      } else postState


      // If receiver variable is materialized, use that,
      // otherwise check if it is the "@this" var and use materialized "@this" if it exists
      val materializedReceiver = receiverOption.flatMap(recVar =>
        if(postState2.get(recVar).isDefined){
          postState2.get(recVar)
        }else if(w.getThisVar(retloc).contains(recVar)){
          // invoke on variable representing @this
          val r = postState2.get(LocalWrapper("@this", "_"))
          r
        }else None
      )
      // Create call stack frame with return value
      val receiverTypesFromPT: Option[TypeSet] = receiverOption.map{
        case rec@LocalWrapper(_,_) =>
          w.pointsToSet(mloc, rec)
        case _ => throw new IllegalStateException()
      }
      val newFrame = CallStackFrame(mRet, Some(AppLoc(mloc, line, true)), retVal)
      val clearedLVal = cmd match {
        case AssignCmd(target, _, _) => postState2.clearLVal(target)
        case _ => postState2
      }
      val stateWithFrame =
        clearedLVal.setCallStack(newFrame :: clearedLVal.callStack).copy(nextCmd = List(target), alternateCmd = Nil)
      // Constraint receiver by current points to set  TODO: apply this to other method transfers ====
      val outStates = if(receiverTypesFromPT.isDefined) {
        val (thisV, stateWThis) = if(materializedReceiver.isEmpty) {
          stateWithFrame.getOrDefine(LocalWrapper("@this", "_"),Some(mRet.loc))
        } else {
          val v = materializedReceiver.get
          (v,stateWithFrame.defineAs(LocalWrapper("@this","_"), v))
        }
        thisV match{
          case thisV:PureVar => {
            val pts = stateWThis.typeConstraints.get(thisV).map(_.intersect(receiverTypesFromPT.get))
              .getOrElse(receiverTypesFromPT.get)
            Set(stateWThis.addTypeConstraint(thisV, pts))
          }
          case NullVal =>
            Set[State]()
        }
      } else {
        Set(stateWithFrame)
      }
      outStates
    case (retLoc@AppLoc(mloc, line, false), mRet@SkippedInternalMethodReturn(_, _, rel, _)) =>
      // Create call stack frame with return value
      val newFrame = CallStackFrame(mRet, Some(AppLoc(mloc,line,true)), Map())
      // Remove assigned variable from the abstract state
      val clearedLval = w.cmdAtLocation(retLoc) match{
        case AssignCmd(target, _:Invoke, _) =>
          postState.clearLVal(target)
        case _ => postState
      }
      val withStackFrame = clearedLval.setCallStack(newFrame :: clearedLval.callStack)
        .copy(nextCmd = List(target), alternateCmd = Nil)
      val withPrecisionLoss = rel.applyPrecisionLossForSkip(withStackFrame)
      Set(withPrecisionLoss)
    case (SkippedInternalMethodReturn(_,_,_,_), SkippedInternalMethodInvoke(_,_,_)) =>
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (mr@InternalMethodReturn(_,_,_), cmd@AppLoc(m,_,false)) =>
      // if @this is defined, constrain to be subtype of receiver class
      val postStateWithThisTC = postState.get(LocalWrapper("@this","_")) match{
        case Some(thisPv:PureVar) if postState.typeConstraints.contains(thisPv)=>
          val oldTc = postState.typeConstraints(thisPv)
          val newTc = oldTc.filterSubTypeOf(Set(m.classType))
          postState.addTypeConstraint(thisPv, newTc)
        case _ => postState
      }
      val out = w.cmdAtLocation(cmd) match{
        case ReturnCmd(_,_) => Set(postStateWithThisTC)
        case _ => throw new IllegalStateException(s"malformed bytecode, source: $mr  target: ${cmd}")
      }
      out.map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case (_:AppLoc, _:InternalMethodInvoke) =>
      Set(postState).map(_.copy(nextCmd = List(source), alternateCmd = Nil))
    case t =>
      println(t)
      ???
  }
  private def futureCLInit(state:State, clazz:String):Boolean = { //TODO:====================   call this on ci/cb
    state.traceAbstraction.rightOfArrow.exists{
        case CLInit(sig) => sig == clazz
        case _:FreshRef => false
        case _:AbsMsg => false
      }
  }

  /**
   * For a back message with a given package and name, instantiate each rule as a new trace abstraction
   * @param appMethod method associated with callin or callback transfer
   *                  (callback for callback invoke/return, containing method for callin/return)
   * @param postState state at point in app just before back message
   * @return a new trace abstraction for each possible rule
   */
  def newMsgTransfer(appMethod:MethodLoc, mt: MessageType,
                     sig:Signature, allVar:List[Option[RVal]],
                     postState: State): Set[State] = {
    val freshI: Option[AbsMsg] = specSpace.getIWithMergedVars(mt,sig, Some(allVar))
    freshI match {
      case None => Set(postState)
      case Some(i) =>
        val allVars: Seq[(Option[RVal], PureExpr)] = (allVar zip i.lsVars)
        val (stateAfterAssign, assignTo) = allVars.headOption match {
          case Some((_, TopVal)) => (postState,TopVal) //case where spec doesn't care about assign value
          case Some((Some(lVal:LVal), _)) =>
            val valOfAssign = postState.get(lVal)
            if(valOfAssign.contains(NullVal)
              && mt == CIExit && nonNullCallins.exists(nnCi => nnCi.contains(CIExit, sig))){
              return Set()
            }
            // clear return value from post state if its a callin
            val postStateHandledRetVal = if(mt == CIExit) postState.clearLVal(lVal) else postState
            (postStateHandledRetVal,valOfAssign.getOrElse(TopVal))
          case Some((None, _)) => (postState,TopVal) //code assigns to nothing
          case None => (postState,TopVal)
        }
        val iWithRet = i.copyMsg(lsVars = assignTo::Nil)
        val (newState,newI) = if (allVars.nonEmpty) allVars.tail.foldLeft((stateAfterAssign, iWithRet)){
          case ((acc,i), (None, _)) =>
            (acc,i.copyMsg(lsVars = i.lsVars.appended(TopVal)))
          case ((acc,i), (_, TopVal)) =>
            (acc,i.copyMsg(lsVars = i.lsVars.appended(TopVal)))
          case ((acc,i),(Some(rVal),_)) =>
            val (pv, stateDef) = acc.getOrDefine(rVal, Some(appMethod))
            (stateDef, i.copyMsg(lsVars = i.lsVars.appended(pv)))
          case ((acc,i),(v1,v2)) =>
            println(acc)
            println(i)
            println(v1)
            println(v2)
            ???
        } else (stateAfterAssign, iWithRet)
//        val c = newModelVars.traceAbstraction.filter(t => t.a.isEmpty)
        val oldAbs = newState.traceAbstraction
        val newAbs = oldAbs.copy(rightOfArrow = newI::oldAbs.rightOfArrow)
        Set(newState.copy(sf = newState.sf.copy(traceAbstraction = newAbs)))
    }
  }

  /**
   * Get input and output vars of executing part of the app responsible for an observed message
   * Note: all vars used in invoke or assign/invoke can be in post state
   * @param loc transfer location (not AppLoc)
   * @return (pkg, function name)
   */
  private def msgCmdToMsg(loc: Loc): Signature =
    loc match {
      case CallbackMethodReturn(sig, _,_) => sig
      case CallbackMethodInvoke(sig, _) => sig
      case CallinMethodInvoke(sig) => sig
      case CallinMethodReturn(sig) => sig
      case GroupedCallinMethodInvoke(targetClasses, fmwName) => Signature(targetClasses.head, fmwName)
      case GroupedCallinMethodReturn(targetClasses,fmwName) => Signature(targetClasses.head, fmwName)
      case v =>
        throw new IllegalStateException(s"No command message for $v")
    }

  private def exprContainsV(value: PureVar, expr:PureExpr):Boolean = expr match{
    case p:PureVar => value == p
    case _:PureVal => false
  }
  private def existsNullConstraint(v:PureVar,state:State):Boolean = {
    val equiv = state.equivPv(v)
    equiv.exists { vv =>
      state.pureFormula.exists {
        case PureConstraint(lhs, Equals, NullVal) if lhs == vv => true
        case PureConstraint(NullVal, Equals, rhs) if rhs == vv => true
        case _ => false
      }
    }
  }
  private def heapCellReferencesVAndIsNonNull(value:PureVar, state: State): Boolean = state.heapConstraints.exists{
    case (FieldPtEdge(base, _), ptVal) =>
      if(value == base || exprContainsV(value,ptVal)) {
        ptVal != NullVal && (ptVal match {
          case ptVal: PureVar => (! existsNullConstraint (ptVal, state) )
          case _ => true
        })
      } else false
    case (StaticPtEdge(_,_),ptVal) => exprContainsV(value,ptVal)
    case (ArrayPtEdge(base,index),ptVal) =>
      exprContainsV(value,base) || exprContainsV(value,index) || exprContainsV(value,ptVal)
  }
  private def localReferencesV(pureVar: PureVar, state: State): Boolean ={
    state.callStack.exists{
      case MaterializedCallStackFrame(_,_,locals) =>
        locals.exists{
          case (_,v) =>
            v == pureVar
        }
      case _ => false
    }
  }
//  private def encodeExistingNotEqualTo(pureVar:PureVar, state:State):State = {
//    val localConstraints = state.sf.callStack.flatMap{sf =>
//      sf.locals.map{case (_,value) =>
//        PureConstraint(value, NotEquals, pureVar)
//      }
//    }
//    val heapConstraints = state.sf.heapConstraints.flatMap{
//      case (FieldPtEdge(p, _), value) =>
//        if(value == NullVal || existsNullConstraint(value,state)) {
//          Set()
//        }else if(!value.isInstanceOf[PureVar]){
//          Set(PureConstraint(p, NotEquals, pureVar))
//        } else{
//          Set(PureConstraint(p, NotEquals, pureVar), PureConstraint(value, NotEquals, pureVar))
//        }
//      case (StaticPtEdge(_,_),value:PureVar) => Set(PureConstraint(value, NotEquals, pureVar))
//      case (ArrayPtEdge(_,_), value:PureVar) => Set(PureConstraint(value, NotEquals, pureVar))
//      case _ => Set()
//    }
//    state.copy(sf = state.sf.copy(pureFormula = state.sf.pureFormula ++ localConstraints ++ heapConstraints))
//  }

  def cmdTransfer(cmd:CmdWrapper, state:State):Set[State] = cmd match {
    case IfElse(condition, cmdTrue, cmdFalse, loc) => ???
    case AssignCmd(lhs: LocalWrapper, TopExpr(_), _) => Set(state.clearLVal(lhs))
    case AssignCmd(lhs@LocalWrapper(_, _), NewCommand(_), _) =>
      // x = new T
      // Note that T is not important here since the points to analysis should enforce such constraints
      state.get(lhs) match {
        case Some(v: PureVar) =>
          if(state.sf.typeConstraints.get(v).forall(_.isEmpty))
            return Set() // Type constraint must not be empty if new value assigned
          // clear x from state
          val stateWOX = state.clearLVal(lhs)
          ////return stateWOX to disable refCreate in abs trace
          //Set(stateWOX) //Note: refCreate disable prevents many of the tests from working
          // Constrain state for initialization
          val notRef = addRefCreateToState(stateWOX,v).toSet
          // Remove x local
          notRef.map(_.clearLVal(lhs))
        case Some(_: PureVal) => Set() // new cannot return anything but a pointer
        case None => Set(state) // Do nothing if variable x is not in state
      }
    case AssignCmd(lw: LocalWrapper, ThisWrapper(thisTypename), a) =>
      val out = cmdTransfer(AssignCmd(lw, LocalWrapper("@this", thisTypename), a), state)
      out.map { s =>
        s.get(LocalWrapper("@this", thisTypename)) match {
          case Some(v) =>
            s.addPureConstraint(PureConstraint(v, NotEquals, NullVal))
          case None => s
        }
      }
    case AssignCmd(lhs: LocalWrapper, rhs: LocalWrapper, _) => //
      // x = y
      val lhsv = state.get(lhs) // Find what lhs pointed to if anything
      lhsv.map(pexpr => {
        // remove lhs from abstract state (since it is assigned here)
        val state2 = state.clearLVal(lhs)
        if (state2.containsLocal(rhs)) {
          // rhs constrained by refutation state, lhs should be equivalent
          Set(state2.addPureConstraint(PureConstraint(pexpr, Equals, state2.get(rhs).get)))
        } else {
          // rhs unconstrained by refutation state, should now be same as lhs
          val state3 = state2.defineAs(rhs, pexpr)
          Set(state3)
        }
      }).getOrElse {
        Set(state) // if lhs points to nothing, no change in state
      }
    case ReturnCmd(Some(v), _) =>
      val fakeRetLocal = LocalWrapper("@ret", "_")
      val retv = state.get(fakeRetLocal)
      val state1 = state.clearLVal(fakeRetLocal)
      Set(retv.map(state1.defineAs(v, _)).getOrElse(state))
    case ReturnCmd(None, _) => Set(state)
    case AssignCmd(lhs: LocalWrapper, FieldReference(base, fieldType, _, fieldName), l) =>
      // x = y.f
      val (yval, stateWithY) = state.getOrDefine(base,Some(l.method))
      val mayWrite = yval match{
        case v:PureVar => filterResolver.cellMayBeWritten(w,FieldPtEdge(v,fieldName), stateWithY)
        case _ =>
          false  // cannot be written if base must be null
      }
      if(mayWrite) {
        state.get(lhs) match { //TODO: some kind of imprecision here or in the simplification shown by "Test dynamic dispatch 2"
          case Some(lhsV) => {
            val (basev, state1) = state.getOrDefine(base, Some(l.method))
            // get all heap cells with correct field name that can alias
            val possibleHeapCells = state1.heapConstraints.filter {
              case (FieldPtEdge(pv, heapFieldName), materializedTgt) =>
                val fieldEq = fieldName == heapFieldName
                //TODO: === check that contianing method works here
                lazy val canAlias = state1.canAlias(pv, l.containingMethod.get, base, w, inCurrentStackFrame = true)
                lazy val tgtCanAlias = state1.canAliasEE(lhsV, materializedTgt)
                fieldEq && canAlias && tgtCanAlias //TODO: is target ok here?
              case _ =>
                false
            }
            val statesWhereBaseAliasesExisting: Set[State] = possibleHeapCells.map {
              case (FieldPtEdge(p, _), heapV) =>
                state1.addPureConstraint(PureConstraint(basev, Equals, p))
                  .addPureConstraint(PureConstraint(lhsV, Equals, heapV))
              case _ => throw new IllegalStateException()
            }.toSet

            basev match {
              case basev: PureVar => {
                val heapCell = FieldPtEdge(basev, fieldName)

                val pfFromExisting = if (state1.sf.heapConstraints.contains(heapCell)) {
                  val oldV = state1.sf.heapConstraints(heapCell)
                  Some(PureConstraint(oldV, Equals, lhsV))
                } else {
                  None
                }
                val stateWhereNoHeapCellIsAliased = state1.copy(sf = state1.sf.copy(
                  heapConstraints = state1.heapConstraints + (heapCell -> lhsV),
                  pureFormula = state1.pureFormula ++ pfFromExisting ++ possibleHeapCells.map {
                    case (FieldPtEdge(p, _), _) => PureConstraint(p, NotEquals, basev)
                    case _ => throw new IllegalStateException()
                  }
                ))
                val res = statesWhereBaseAliasesExisting + stateWhereNoHeapCellIsAliased
                res.map(s => s.clearLVal(lhs))
              }
              case _: PureVal => Set.empty
            }
          }
          case None => if (canWeaken) Set(state) else {
            val (yPointsTo, stateWithMaterializedY) = state.getOrDefine(base, Some(l.method))
            Set(stateWithMaterializedY.addPureConstraint(PureConstraint(yPointsTo, NotEquals, NullVal)))
          }
        }
      }else {
        println(s"not materializing dynamic field ${fieldName} because it cannot be written. Method: ${state.sf.callStack.headOption}")
        Set(state.clearLVal(lhs))
      }
    case AssignCmd(FieldReference(base, fieldType, _, fieldName), rhs, l) =>
      // x.f = y
      val (basev, state2) = state.getOrDefine(base, Some(l.method))

      // get all heap cells with correct field name that can alias
      val possibleHeapCells = state2.heapConstraints.filter {
        case (FieldPtEdge(pv, heapFieldName), materializedTgt) =>
          val fieldEq = fieldName == heapFieldName
          val canAlias = state2.canAlias(pv,l.containingMethod.get, base,w, inCurrentStackFrame = true)
          val tgtCanAlias = rhs match{
            case rhsL:LocalWrapper if state2.containsLocal(rhsL) => {
              state2.canAliasEE(materializedTgt, state2.get(rhsL).get)
            }
            case rhsV => {
              state2.get(rhsV).forall { rhsAV =>
                val canEq = state2.canAliasEE(materializedTgt, rhsAV)
                canEq
              }
            }
            case _ => true
          }
          fieldEq && canAlias && tgtCanAlias //TODO: is target alias OK here?
        case _ =>
          false
      }

      // Get or define right hand side
      val possibleRhs = Set(state2.getOrDefine2(rhs, l.method))
      // get or define base of assignment
      // Enumerate over existing base values that could alias assignment
      // Enumerate permutations of heap cell and rhs
      // TODO: remove repeatingPerm here since only one possible rhs
      val perm = BounderUtil.repeatingPerm(a => if (a == 0) possibleHeapCells else possibleRhs, 2)
      val casesWithHeapCellAlias: Set[State] = perm.map {
        case (pte@FieldPtEdge(heapPv, _), tgtVal: PureExpr) :: (rhsPureExpr: PureExpr, state3: State) :: Nil =>
          val withPureConstraint = state3.addPureConstraint(PureConstraint(basev, Equals, heapPv))
          val swapped = withPureConstraint.copy(sf = withPureConstraint.sf.copy(
            heapConstraints = withPureConstraint.heapConstraints - pte,
            pureFormula = (withPureConstraint.pureFormula +
              PureConstraint(tgtVal, Equals, rhsPureExpr) +
              PureConstraint(heapPv, NotEquals, NullVal)) ++ // Base must be non null for normal control flow
              possibleHeapCells.removed(pte).flatMap{ // ensure we don't lose disaliasing info
                case (FieldPtEdge(otherHeapPv, fieldName),_) if !canWeaken =>
                  Some(PureConstraint(otherHeapPv, NotEquals, heapPv))
                case (_,_) if canWeaken => None
              }
          ))
          swapped
        case v =>
          println(v)
          ???
      }.toSet
      val caseWithNoAlias =
        if(possibleHeapCells.nonEmpty || !canWeaken) { //must always materialize x for under approx
          state2.copy(sf = state2.sf.copy(pureFormula = state2.pureFormula ++ possibleHeapCells.flatMap {
            case (FieldPtEdge(pv, _), _) => Some(PureConstraint(basev, NotEquals, pv))
            case _ => None
          }))
        }else {state} // if nothing can alias heap cell and over-approx mode then don't materialize

      val caseWithNoAliasPosUnder = if(canWeaken) caseWithNoAlias else caseWithNoAlias.addPureConstraint(PureConstraint(basev, NotEquals, NullVal))
      casesWithHeapCellAlias + caseWithNoAliasPosUnder
    case AssignCmd(target: LocalWrapper, source, l) if source.isConst =>
      state.get(target) match {
        case Some(v) =>
          val src = Set(state.getOrDefine2(source, l.method))
          src.map {
            case (NullVal, s2) =>
              s2.addPureConstraint(PureConstraint(v, Equals, NullVal))
                .clearLVal(target)
            case (pexp, s2) =>
              s2.addPureConstraint(PureConstraint(v, Equals, pexp))
                .addPureConstraint(PureConstraint(v,NotEquals,NullVal)) // non-null if non-null const
                .clearLVal(target)
          }
        case None => Set(state)
      }
    case _: InvokeCmd => Set(state) // Invoke not relevant and skipped
    case AssignCmd(_, _: Invoke, _) =>
      if(canWeaken) Set(state) else
        ???
    case Goto(b, trueLoc, l) =>
      if (state.nextCmd.toSet.size == 1) {
        val stateLocationFrom: Loc = state.nextCmd.head
        if (stateLocationFrom == trueLoc)
          assumeInState(l.method, b, state, negate = false).toSet
        else
          assumeInState(l.method, b, state, negate = true).toSet
      } else if (state.nextCmd.toSet.size == 2){
        Set(state) // When both true and false branch is represented by current state, do not materialize anything
      }else{
        throw new IllegalStateException(s"If should only have 1 or 2 next commands, next commands: ${state.nextCmd}")
      }
    case AssignCmd(l,Cast(castT, local),cmdloc) =>
      val state1 = state.get(local) match{
        case Some(v:PureVar) => state.constrainUpperType(v, castT, ch)
        case Some(_) => state
        case None => state
      }
      cmdTransfer(AssignCmd(l,local,cmdloc),state1)
    case AssignCmd(l:LocalWrapper, ref@StaticFieldReference(declaringClass, fname, containedType, Some(v)), _) =>
      state.get(l) match {
        case Some(value:PureVar) =>
          val constrained = state.addPureConstraint(PureConstraint(value, Equals, v))
          Set(constrained.clearLVal(l))
        case Some(value:PureVal) =>
          if(value == v){
            Set(state.clearLVal(l))
          }else
            Set()
        case None =>
          Set(state)
      }
    case AssignCmd(l:LocalWrapper, ref@StaticFieldReference(declaringClass, fname, containedType, None), _) =>
      if(filterResolver.cellMayBeWritten(w,StaticPtEdge(declaringClass,fname), state)) {
        if (fname.contains("R$id")) {
          Set(state.clearLVal(l)) // Don't care about this always just set to integer
        } else {
          if (state.containsLocal(l)) {
            val v = state.get(l).get
            val state1 = state.clearLVal(l)
            Set(state1.copy(sf =
              state1.sf.copy(heapConstraints = state1.heapConstraints + (StaticPtEdge(declaringClass, fname) -> v),
              )).constrainUpperType(v, containedType, ch))
          } else Set(state)
        }
      }else{
        println(s"not materializing static field ${declaringClass} ${fname} because it cannot be written. Method: ${state.sf.callStack.headOption}")
        Set(state.clearLVal(l))
      }

    case cmd@AssignCmd(StaticFieldReference(declaringClass,fieldName,_,Some(v)), l,_) =>
      throw new IllegalArgumentException(s"assign to constant field??? ${cmd} field should only hold ${v}")
    case AssignCmd(StaticFieldReference(declaringClass,fieldName,_,None), l,_) =>
      val edge = StaticPtEdge(declaringClass, fieldName)
      if(state.heapConstraints.contains(edge)){
        val v = state.heapConstraints(edge)
        val state1 = state.defineAs(l, v)
        Set(state1.copy(sf = state1.sf.copy(heapConstraints = state1.heapConstraints - edge)))
      }else Set(state)
    case NopCmd(_) => Set(state)
    case ThrowCmd(v) => Set() //TODO: implement exceptional control flow
    case SwitchCmd(_,targets,_) => //TODO: precise implementation of switch
      targets.foldLeft(Set[State]()){
        case (acc, cmd) => acc ++ cmdTransfer(cmd, state)
      }
    case AssignCmd(lhs:LocalWrapper, ArrayReference(base, index),_) =>
      state.get(lhs) match{
        case Some(v) =>
          //TODO: We currently do not precisely track array references
          // Dropping the constraint should be sound but not precise
//          val (basev,state1) = state.getOrDefine(base)
//          val (indexv,state2) = state1.getOrDefine(index)
//          val arrayRef = ArrayPtEdge(basev, indexv)
//          Set(state2.copy(heapConstraints = state2.heapConstraints + (arrayRef -> v)).clearLVal(lhs))
          Set(state.clearLVal(lhs))
        case None => Set(state)
      }
    case AssignCmd(ArrayReference(base,index), lhs,_) =>
      val possibleAliases: Map[HeapPtEdge, PureExpr] = state.heapConstraints.filter{
        case (ArrayPtEdge(_,_),_) => true
        case _ => false
      }
      if (possibleAliases.isEmpty)
        Set(state)
      else {
        //TODO: handle arrays more precisely
        Set(state.copy(sf = state.sf.copy(heapConstraints = state.heapConstraints -- possibleAliases.keySet)))
      }

    case AssignCmd(lhs:LocalWrapper, ArrayLength(l),_) =>
      state.get(lhs) match{
        case Some(v) =>
          //TODO: handle arrays more precisely
          val state1 = state.clearLVal(lhs)
          Set(state1.copy(sf = state1.sf.copy(pureFormula = state.pureFormula + PureConstraint(v, NotEquals, NullVal))))
        case None => Set(state)
      }
    case AssignCmd(_:LocalWrapper, CaughtException(_), _) =>
      Set[State]() //TODO: handle exceptional control flow
    case AssignCmd(lhs:LocalWrapper, Binop(_,_,_),_) if state.get(lhs).isEmpty =>
      Set(state) // lhs of binop is not defined
    case AssignCmd(lhs:LocalWrapper, Binop(_,_,_), _) =>
      // TODO: sound to drop constraint, add precision when necessary
      Set(state.clearLVal(lhs))
    case AssignCmd(lhs:LocalWrapper, InstanceOf(clazz, target),_) =>
      // TODO: sound to drop constraint, handle instanceof when needed
      state.get(lhs) match{
        case Some(v) => Set(state.clearLVal(lhs))
        case None => Set(state)
      }
    case c =>
      println(c)
      ???
  }

  private def getOrDefineRVals(method: MethodLoc, rhs:List[Option[RVal]], state:State): (List[Option[PureExpr]], State) ={
    rhs.foldRight((List[Option[PureExpr]](),state)){
      case (Some(rval),(vlist,cstate)) =>
        val (pexp,nstate) = cstate.getOrDefine2(rval,method)//enumerateAliasesForRVal(rval,cstate)
        (Some(pexp)::vlist, nstate)
      case (None,(vlist,cstate)) => (None::vlist, cstate)
    }
  }
  def addRefCreateToState(state:State, pv:PureVar):Option[State] = {
    //            val t = AbstractTrace(Not(ref), Nil, Map(ref.v -> thisV))
    val pvNNState = state.addPureConstraint(PureConstraint(pv, NotEquals, NullVal))
    //TODO: possibly constrain heap cel targets of pv to null here
    val equivPvList = state.equivPv(pv)
    val heapMemRef = equivPvList.exists(equivPv =>
      heapCellReferencesVAndIsNonNull(equivPv,pvNNState)) || localReferencesV(pv,pvNNState)
    if(heapMemRef)
      None
    else {
      val ref = FreshRef(pv) // specSpace.getRefWithFreshVars()
      val ot = pvNNState.sf.traceAbstraction
      val nt = ot.copy(rightOfArrow = ref::ot.rightOfArrow) //ot.copy(rightOfArrow = ref::ot.rightOfArrow, modelVars = ot.modelVars + (ref.v->pv))
      Some(pvNNState.copy(sf = pvNNState.sf.copy(traceAbstraction = nt)))
    }
  }

  def isEnum(v:RVal, method:MethodLoc):Boolean = v match {
    case LocalWrapper(name, t) =>
      method.getLocals().exists{
        case (lname,ltype) if lname == name =>
          val superC = classHierarchyConstraints.getSupertypesOf(ltype)
          superC.contains("java.lang.Enum")
        case _ => false
      }
    case _ => false // enums seem to always be in locals?
  }
  def assumeInState(method:MethodLoc, bExp:RVal, state:State, negate: Boolean): Option[State] = bExp match{
    case BoolConst(b) if b != negate => Some(state)
    case BoolConst(b) if b == negate => None
    case Binop(v1, _, v2) if isEnum(v1,method) || isEnum(v2,method) => Some(state) //TODO: ignoring enum for now
    case Binop(v1, op, v2) =>
      val (v1Val, state0) = state.getOrDefine2(v1, method)
      val (v2Val, state1) = state0.getOrDefine2(v2, method)
      //TODO: Handle boolean expressions, integer expressions, etc
      // it is sound, but not precise, to drop constraints
      Some((op, negate) match {
        case (Eq, false) => state1.addPureConstraint(PureConstraint(v1Val, Equals, v2Val))
        case (Ne, false) => state1.addPureConstraint(PureConstraint(v1Val, NotEquals, v2Val))
        case (Eq, true) => state1.addPureConstraint(PureConstraint(v1Val, NotEquals, v2Val))
        case (Ne, true) => state1.addPureConstraint(PureConstraint(v1Val, Equals, v2Val))
        case _ => state
      })
    case v =>
      throw new IllegalStateException(s"Invalid rval for assumeInState: $v")
  }
}
