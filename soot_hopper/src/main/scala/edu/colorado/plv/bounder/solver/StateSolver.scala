package edu.colorado.hopper.solver

import edu.colorado.plv.bounder.ir.CBEnter
import edu.colorado.plv.bounder.lifestate.LifeState
import edu.colorado.plv.bounder.lifestate.LifeState.{And, I, LSAbsBind, LSAtom, LSFalse, LSPred, LSTrue, NI, Not, Or}
import edu.colorado.plv.bounder.symbolicexecutor.state._

import scala.collection.immutable
import scala.reflect.ClassTag

trait Assumptions

class UnknownSMTResult(msg : String) extends Exception(msg)

/** SMT solver parameterized by its AST or expression type */
trait StateSolver[T] {
  // checking
  def checkSAT() : Boolean
  def checkSATWithAssumptions(assumes : List[String]) : Boolean

  def getUNSATCore : String
  def push() : Unit
  def pop() : Unit

  // cleanup
  def dispose() : Unit
  // conversion from pure constraints to AST type of solver (T)
//  def mkAssert(p : PureConstraint) : Unit = mkAssert(toAST(p))
//  def mkAssertWithAssumption(assume : String, p : PureConstraint) : Unit = mkAssert(mkImplies(mkBoolVar(assume), toAST(p)))

  // quantifiers
  /**
   * forall int condition is true
   * @param cond
   */
  protected def mkForallInt(min:T, max:T, cond:T=>T):T
  // comparison operations
  protected def mkEq(lhs : T, rhs : T) : T
  protected def mkNe(lhs : T, rhs : T) : T
  protected def mkGt(lhs : T, rhs : T) : T
  protected def mkLt(lhs : T, rhs : T) : T
  protected def mkGe(lhs : T, rhs : T) : T
  protected def mkLe(lhs : T, rhs : T) : T

  // logical and arithmetic operations
  protected def mkImplies(t: T, t1: T):T
  protected def mkNot(o : T) : T
  protected def mkAdd(lhs : T, rhs : T) : T
  protected def mkSub(lhs : T, rhs : T) : T
  protected def mkMul(lhs : T, rhs : T) : T
  protected def mkDiv(lhs : T, rhs : T) : T
  protected def mkRem(lhs : T, rhs : T) : T
  protected def mkAnd(lhs:T, rhs:T):T
  protected def mkAnd(t : List[T]) : T
  protected def mkOr(lhs : T, rhs : T) : T
  protected def mkExactlyOneOf(l:List[T]) : T

  // creation of variables, constants, assertions
  protected def mkIntVal(i : Int) : T
  protected def mkBoolVal(b : Boolean) : T
  protected def mkIntVar(s : String) : T
  protected def mkFreshIntVar(s:String):T
  protected def mkBoolVar(s : String) : T
  protected def mkObjVar(s:PureVar) : T //Symbolic variable
  protected def mkModelVar(s:String, predUniqueID:String):T // model vars are scoped to trace abstraction
  protected def mkAssert(t : T) : Unit
  protected def mkFieldFun(n: String): T
  protected def fieldEquals(fieldFun: T, t1 : T, t2: T):T
  protected def solverSimplify(t: T, state:State, msgname:T, logDbg:Boolean = false): Option[T]
  protected def mkTypeConstraint(typeFun: T, addr: T, tc: TypeConstraint):T
  protected def createTypeFun():T
  protected def mkEnum(name:String, types:List[String]):T
  protected def getEnumElement(enum:T, i:Int):T
  // function traceIndex -> msg
  protected def mkTraceFn(uid:String):T
  // function msg -> iname
  protected def mkINameFn(enum:T, uid:String):T
  // function for argument i -> msg -> value
  protected def mkArgFun(uid:String):T
  // Get enum value for I based on index
  protected def mkIName(enum:T, enumNum:Int):T
  // function from index to message (message is at index in trace)
  protected def mkTraceConstraint(traceFun:T, index:T):T
  // function msg -> funname
  protected def mkNameConstraint(nameFun:T, msg:T):T
  // function argumentindex -> msg -> argvalue
  protected def mkArgConstraint(argFun:T, argIndex:T, msg:T):T
  def printDbgModel(msgname: T, traceabst: Set[TraceAbstraction]):Unit
  //protected def mkIFun(atom:I):T
  //protected def mkINIConstraint(fun: T, index: T, modelVars: List[T]):T
  //protected def mkIndArgFun(uid:String):T
  //protected def mkIndArgConstraint(argFun:T, index:T, argnumber:T):T

  def toAST(p : PureConstraint, typeFun: T) : T = p match {
      // TODO: field constraints based on containing object constraints
    case PureConstraint(lhs: PureVar, TypeComp, rhs:TypeConstraint) =>
      mkTypeConstraint(typeFun, toAST(lhs), rhs)
    case PureConstraint(lhs, op, rhs) =>
      toAST(toAST(lhs), op, toAST(rhs))
    case _ => ???
  }
  def toAST(p : PureExpr) : T = p match {
    case p:PureVar => mkObjVar(p)
    case NullVal => mkIntVal(0)
    case ClassType(t) => ??? //handled at a higher level
    case _ =>
      ???
  }
  def toAST(lhs : T, op : CmpOp, rhs : T) : T = op match {
    case Equals => mkEq(lhs,rhs)
    case NotEquals => mkNe(lhs, rhs)
    case _ =>
      ???
  }
  private def assertIAt(index:T, m:I, ienume:T, enumMap: Map[String,Int], uniqueID:String):T = {
    if (!enumMap.contains(m.identitySignature))
      println()
    val tracefun = mkTraceFn(uniqueID)
    val msgExpr = mkTraceConstraint(tracefun, index)
    val nameFun = mkINameFn(ienume, uniqueID)
    mkAnd(mkEq(mkNameConstraint(nameFun,msgExpr), mkIName(ienume,enumMap(m.identitySignature))),
      mkAnd(m.lsVars.zipWithIndex.map{case (msgvar, ind) =>
        mkEq(mkArgConstraint(mkArgFun(uniqueID), mkIntVal(ind), msgExpr),mkModelVar(msgvar, uniqueID) )}))
  }
  def encodePred(combinedPred: LifeState.LSPred, uid:String, len:T,
                 ienume:T, enumMap:Map[String,Int], maxLen:Option[Int]): T = combinedPred match {
    case And(l1, l2) => mkAnd(encodePred(l1, uid,len, ienume, enumMap,maxLen), encodePred(l2, uid,len,ienume, enumMap,maxLen))
    case LSAbsBind(k, v: PureVar) => mkEq(mkModelVar(k, uid), mkObjVar(v))
    case Or(l1, l2) => mkOr(encodePred(l1, uid,len,ienume,enumMap,maxLen), encodePred(l2, uid,len,ienume, enumMap,maxLen))
    case Not(l) => mkNot(encodePred(l, uid,len, ienume, enumMap,maxLen))
    case m@I(_,_, lsVars) => {
      val i = mkFreshIntVar("fromi")
      val iconstraints = mkAnd(List(
        mkLt(mkIntVal(-1), i),
        mkLt(i,len),
        assertIAt(i, m, ienume, enumMap, uid)
      ))
      maxLen match{
        case Some(v) => mkAnd(iconstraints, mkLt(i, mkIntVal(v)))
        case None => iconstraints
      }
    }
    case NI(m1,m2) => {
      // exists i such that omega[i] = m1 and forall j > i omega[j] != m2
      val i = mkFreshIntVar("fromni")
      val niConstraints = mkAnd(List(
        mkLt(mkIntVal(-1),i),
        mkLt(i,len),
        assertIAt(i, m1, ienume, enumMap, uid),
        mkForallInt(i,len, j => mkNot(assertIAt(j, m2, ienume, enumMap, uid)))
      ))
      maxLen match {
        case Some(v) => mkAnd(niConstraints, mkLt(i,mkIntVal(v)))
        case None => niConstraints
      }
    }
  }


  def allITraceAbs(traceAbstractionSet: Set[TraceAbstraction], includeArrow:Boolean=false):Set[I] =
    traceAbstractionSet.flatMap(a => allI(a,includeArrow))
  def allI(pred:LSPred):Set[I] = pred match{
    case i@I(_,_,_) => Set(i)
    case NI(i1,i2) => Set(i1,i2)
    case And(l1,l2) => allI(l1).union(allI(l2))
    case Or(l1,l2) => allI(l1).union(allI(l2))
    case Not(l) => allI(l)
    case LSTrue => Set()
    case LSFalse => Set()
    case LSAbsBind(_,_) => Set()
  }
  def allI(abs:TraceAbstraction, includeArrow:Boolean):Set[I] = abs match{
    case AbsFormula(pred) => allI(pred)
    case AbsArrow(pred, i2) =>
      if(includeArrow)
        allI(pred,includeArrow) + i2
      else
        allI(pred,includeArrow)
    case AbsAnd(p1,p2) => allI(p1,includeArrow).union(allI(p2,includeArrow))
    case AbsEq(_,_) => Set()
  }
  def encodeTraceAbs(abs:TraceAbstraction, enum:T, iNameIntMap:Map[String,Int],maxWitness:Option[Int]):T = {
//    val initial_i = mkIntVar(s"initial_i_ + ${System.identityHashCode(abs)}")
//    val initial_i = mkFreshIntVar("i")
    // A unique id for this element of the trace abstraction, used to distinguish model vars and
    val uniqueID = System.identityHashCode(abs).toString
    val len = mkIntVar(s"len_${uniqueID}") // there exists a finite size of the trace

    def ienc(i:T, abs: TraceAbstraction):T = abs match{
      case AbsFormula(f) =>
        encodePred(f, uniqueID, len, enum,iNameIntMap,maxWitness)
      case AbsAnd(f1,f2) => mkAnd(ienc(i,f1), ienc(i,f2))
      case AbsArrow(abs, ipred) => {
        //TODO: somehow enforce that ipred must be later in the trace than the m1 in NI(m1,m2)
        // Do the semantics enforce this?
        val allNestedI = allI(abs,false)
        val j = mkFreshIntVar("jfromarrow")
        val arrowConstraints = mkAnd(List(
          mkLt(mkIntVal(-1), j),
          mkLt(j, i),
          assertIAt(j, ipred,enum, iNameIntMap, uniqueID),
          ienc(j, abs),
          mkForallInt(j,i, k => mkAnd(allNestedI.map{mi => mkNot(assertIAt(k,mi, enum, iNameIntMap, uniqueID))}.toList))
        ))
        maxWitness match{
          case Some(max) => mkAnd(arrowConstraints, mkLt(len,mkIntVal(max)))
          case None => arrowConstraints
        }
      }
      case AbsEq(mv,pv) => mkEq(mkModelVar(mv,uniqueID),mkObjVar(pv))
    }

    // Each position has unique message
    // dummy message for symbols not contained in formula
    val other = I(CBEnter,Set(("","")), Nil)
    //TODO: unit test that causes two messages to occupy same spot

    ienc(len, abs)
  }

  protected def mkDistinct(pvList: Iterable[PureVar]): T

  def toAST(state: State, enum:T, iNameIntMap:Map[String,Int], maxWitness:Option[Int] = None): T = {
    // TODO: make all variables in this encoding unique from other states so multiple states can be run at once
    // TODO: add ls constraints to state
    // TODO: mapping from ? constraints to bools that can be retrieved from the model after solving
    val heap = state.heapConstraints
    val pure = state.pureFormula
    // TODO: handle static fields
    // typeFun is a function from addresses to concrete types in the program
    val typeFun = createTypeFun()

    // pure formula are for asserting that two abstract addresses alias each other or not
    //  as well as asserting upper and lower bounds on concrete types associated with addresses
    val pureAst = pure.foldLeft(mkBoolVal(true))((acc, v) =>
      mkAnd(acc, toAST(v, typeFun))
    )

    // The only constraint we get from the heap is that domain elements must be distinct
    // e.g. a^.f -> b^ * c^.f->d^ means a^ != c^
    // alternatively a^.f ->b^ * c^.g->d^ does not mean a^!=c^
    val fields = heap.groupBy({ case (FieldPtEdge(_, fieldName), _) => fieldName })
    val heapAst = fields.foldLeft(mkBoolVal(true)){(acc,v) =>
      val pvList = v._2.map{case (FieldPtEdge(pv, _), _) => pv}
      mkAnd(acc, mkDistinct(pvList))
    }

    val trace = state.traceAbstraction.foldLeft(mkBoolVal(true)) {
      case (acc,v) => mkAnd(acc, encodeTraceAbs(v, enum, iNameIntMap,maxWitness))
    }
    mkAnd(mkAnd(pureAst, heapAst),trace)
  }

  def enumFromStates(states: List[State]):(T,Map[String,Int]) = {
//    val alli = allITraceAbs(statestate.traceAbstraction,true)
    val alli = allITraceAbs(states.flatMap(_.traceAbstraction).toSet,true)
    //TODO: allI doesn't find cbenter onpause
    val inamelist = "OTHEROTHEROTHER"::(alli.groupBy(_.identitySignature).keySet.toList)
    val iNameIntMap: Map[String, Int] = inamelist.zipWithIndex.toMap
    (mkEnum("inames",inamelist), iNameIntMap)
  }
  def simplify(state: State, logDbg:Boolean = false, maxWitness:Option[Int] = None): Option[State] = {
    push()
    val (ienum,iNameIntMap) = enumFromStates(List(state))
    val ast = toAST(state,ienum, iNameIntMap, maxWitness)

    if(logDbg) {
      println(s"State ${System.identityHashCode(state)} encoding: ")
      println(ast.toString)
    }
    val simpleAst = solverSimplify(ast,state,ienum, logDbg)

    pop()
    // TODO: garbage collect, if purevar can't be reached from reg or stack var, discard
    simpleAst.map(_ => state) //TODO: actually simplify?
  }

  // TODO: call stack is currently just a list of stack frames, this needs to be updated when top is added
  def stackMustImply(cs1: List[CallStackFrame], cs2: List[CallStackFrame]):Boolean = (cs1, cs2) match {
    case (CallStackFrame(ml1, _, locals1)::t1, CallStackFrame(ml2, _, locals2)::t2) if ml1 == ml2 =>
      locals1.forall{case (k,v) => locals2.get(k).map(_==v).getOrElse(false)} &&
        stackMustImply(t1,t2)
    case (Nil,Nil) => true
    case _ => false
  }

  /**
   * Check if formula s1 is entirely contained within s2.  Used to determine if subsumption is possible
   *
   * @param s1
   * @param s2
   * @return
   */
  def mustImply(s1:State, s2:State, bound: Option[Int] = None):Boolean = {
    // Currently, the stack is strictly the app call string
    // When adding more abstraction to the stack, this needs to be modified
    // TODO: check if pure vars are canonacalized
    val si = stackMustImply(s1.callStack, s2.callStack)
    val hi = s1.heapConstraints.forall{case (k,v) => s2.heapConstraints.get(k).map(_ == v).getOrElse(false)}
    val pvi = s1.pureFormula.forall{
      case p@PureConstraint(_, Equals, _) =>
        s2.pureFormula.contains(p)
      case p@PureConstraint(_, NotEquals, _) => s2.pureFormula.contains(p)
      case _ => ??? //TODO: type comparison
    }
    val (ienum,idMap) = enumFromStates(List(s1,s2))
    val phi1 = toAST(s1,ienum,idMap,bound)
    val phi2 = toAST(s2,ienum,idMap,bound)
    val f = mkNot(mkImplies(phi1,phi2))
    push()
    mkAssert(f)
    val ti = checkSAT()
    if (ti) {
      printDbgModel(ienum, s1.traceAbstraction)
    }
    pop()

    si && hi && pvi && (!ti)
  }

}