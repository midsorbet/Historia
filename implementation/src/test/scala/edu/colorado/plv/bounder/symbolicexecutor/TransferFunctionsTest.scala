package edu.colorado.plv.bounder.symbolicexecutor

import better.files.Resource
import edu.colorado.plv.bounder.ir._
import edu.colorado.plv.bounder.lifestate.LifeState.{AbsMsg, LSSpec, OAbsMsg, SetSignatureMatcher, Signature, SignatureMatcher}
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.solver.{ClassHierarchyConstraints, SetInclusionTypeSolving, SolverTypeSolving, StateTypeSolving, Z3StateSolver}
import edu.colorado.plv.bounder.symbolicexecutor.state._
import org.scalatest.funsuite.AnyFunSuite
import com.microsoft.z3.Context
import upickle.default.read

import scala.collection.BitSet

class TransferFunctionsTest extends AnyFunSuite {
  val basePv = PureVar(1)
  val otherPv = PureVar(2)
  val nullPv = PureVar(3)
  val recPv = PureVar(4)
  val esp = new SpecSpace(Set(), Set())
  val ctx = new Context
  val solver = ctx.mkSolver()
  val hierarchy: Map[String, Set[String]] =
    Map("java.lang.Object" -> Set("String", "Foo", "Bar", "java.lang.Object"),
      "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))

  private val classToInt: Map[String, Int] = hierarchy.keySet.zipWithIndex.toMap
  val intToClass: Map[Int, String] = classToInt.map(k => (k._2, k._1))

  object BoundedTypeSet {
    def apply(someString: Some[String], none: None.type, value: Set[Nothing]): TypeSet = someString match{
      case Some(v) =>
        val types = hierarchy(v).map(classToInt)
        val bitSet = BitSet() ++ types
        BitTypeSet(bitSet)
    }
  }
  val miniCha = new ClassHierarchyConstraints(hierarchy,Set("java.lang.Runnable"),
    intToClass)
  private def getStateSolver():(Z3StateSolver, ClassHierarchyConstraints) = {
    (new Z3StateSolver(miniCha, logTimes = true, timeout = Z3TimeoutBehavior()),miniCha)
  }

  implicit def set2SigMat(s:Set[Signature]):SignatureMatcher = SetSignatureMatcher(s)

  val tr = (ir:SerializedIR, cha:ClassHierarchyConstraints) =>
    new TransferFunctions(ir, new SpecSpace(Set()),cha, true,FilterResolver(None))
  def testCmdTransfer(cmd:AppLoc => CmdWrapper, post:State, testIRMethod: SerializedIRMethodLoc):Set[State] = {
    val preloc = AppLoc(testIRMethod,SerializedIRLineLoc(1), isPre=true)
    val postloc = AppLoc(testIRMethod,SerializedIRLineLoc(1), isPre=false)
    val ir = new SerializedIR(Map(postloc -> Set(CmdTransition(preloc, cmd(postloc)))))
    tr(ir,miniCha).transfer(post,preloc, postloc)
  }

  def transferJsonTest(name:String,spec:SpecSpace):Set[State] = {

    val js = ujson.Value(Resource.getAsString(name)).obj
    val state = read[State](js("state"))
    val source = read[Loc](js("source"))
    val target: Loc = read[Loc](js("target"))
    val cmd = read[CmdWrapper](js("cmd"))
    val cha = read[ClassHierarchyConstraints](Resource.getAsString("TestStates/hierarchy2.json"))

//    val ir = SerializedIR(Map(target -> Set[SerializedTransition](CmdTransition(source,cmd))))
//    val transfer = new TransferFunctions(ir, spec,cha,true)
//    val out = transfer.transfer(state, target, source)
//    out
    ???
  }

  //Test transfer function where field is assigned and base may or may not be aliased
  // pre: this -> a^ * b^.out -> b1^ /\ b1^ == null
  // post: (this -> a^ * a^.out -> c^* d^.out -> e^) OR (this -> a^ * a^.out -> c^ AND a^=d^ AND c^=d^)
  val fooMethod = SerializedIRMethodLoc("","foo()", List(Some(LocalWrapper("@this","java.lang.Object"))))
  val fooMethodReturn = CallbackMethodReturn(Signature("", "foo()"), fooMethod, None)
  test("Transfer may or may not alias") {
    val cmd = (loc:AppLoc) => {
      val thisLocal = LocalWrapper("@this", "java.lang.Object")
      AssignCmd(FieldReference(thisLocal, "Object", "Object", "o"), NullConst, loc)
    }
    val post = State(StateFormula(CallStackFrame(fooMethodReturn, None, Map(StackVar("@this") -> basePv))::Nil,
      heapConstraints = Map(FieldPtEdge(otherPv, "o") -> NullVal),
      pureFormula = Set(),
      traceAbstraction = AbstractTrace(Nil),
      typeConstraints = Map()),
      nextAddr = 0
    )

    val pre = testCmdTransfer(cmd, post, fooMethod)
    println(s"pre: ${pre})")
    println(s"post: ${post}")
    assert(pre.size == 2)
    assert(1 == pre.count(state =>
      state.heapConstraints.size == 1
      && state.pureFormula.contains(PureConstraint(basePv,NotEquals, otherPv))))
    assert(pre.count(state => state.heapConstraints.isEmpty) == 1)
  }

  test("Transfer with aliased base of heap cell") {
    val cmd = (loc:AppLoc) => {
      val thisLocal = LocalWrapper("@this", "java.lang.Object")
      AssignCmd(FieldReference(thisLocal, "Object", "Object", "o"), NullConst, loc)
    }
    val post = State(StateFormula(CallStackFrame(fooMethodReturn, None, Map(StackVar("@this") -> basePv))::Nil,
      heapConstraints = Map(FieldPtEdge(basePv, "o") -> otherPv),
      pureFormula = Set(),
      traceAbstraction = AbstractTrace(Nil),
      typeConstraints = Map()),
      nextAddr = 0
    )

    val pre = testCmdTransfer(cmd, post, fooMethod)
    println(s"pre: ${pre})")
    println(s"post: ${post}")
    assert(pre.size == 2)
    assert(1 == pre.count(state =>
      state.heapConstraints.size == 1
        && state.pureFormula.contains(PureConstraint(basePv,NotEquals, basePv))))
    assert(pre.count(state => state.heapConstraints.isEmpty) == 1)
  }
  test("Transfer assign new local") {
    val cmd= (loc:AppLoc) => AssignCmd(LocalWrapper("bar","java.lang.Object"),NewCommand("String"),loc)
    val post = State(StateFormula(
      CallStackFrame(fooMethodReturn, None, Map(StackVar("bar") -> nullPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),
      Map(nullPv -> BitTypeSet(BitSet(1))), AbstractTrace(Nil)),0)
    val prestate: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    println(s"poststate: $post")
    println(s"prestate: $prestate")
    assert(prestate.size == 1)
    val formula = prestate.head.pureFormula
    assert(formula.contains(PureConstraint(nullPv,Equals, NullVal)))
    assert(formula.contains(PureConstraint(nullPv,NotEquals, NullVal)))
  }
  ignore("Transfer assign local local") {
    val cmd= (loc:AppLoc) => AssignCmd(LocalWrapper("bar","Object"),LocalWrapper("baz","String"),loc)
    val post = State(StateFormula(
      CallStackFrame(CallbackMethodReturn(Signature("","foo()"),fooMethod, None), None, Map(StackVar("bar") -> nullPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),Map(), AbstractTrace(Nil)),0)
    val prestate: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    println(s"poststate: $post")
    println(s"prestate: ${prestate}")
    assert(prestate.size == 1)
    val formula = prestate.head.pureFormula
    assert(formula.contains(PureConstraint(nullPv,Equals, NullVal)))
    val csHead = prestate.head.callStack.head.asInstanceOf[MaterializedCallStackFrame]
    assert(csHead.locals.contains(StackVar("baz")))
    assert(!csHead.locals.contains(StackVar("bar")))
  }
  test("Transfer assign from materialized heap cell") {
    implicit val (stSolver, cha) = getStateSolver()
    val x = LocalWrapper("x","Object")
    // bar := x.f
    // post{x -> v-4 bar -> p-5 * v-1.f -> v-2}
    val cmd= (loc:AppLoc) => AssignCmd(LocalWrapper("bar","Object"),FieldReference(x,"Object","Object","f"),loc)
    val post = State(StateFormula(
      CallStackFrame(CallbackMethodReturn(Signature("","foo()"),fooMethod, None), None,
        Map(StackVar("x") -> PureVar(4), StackVar("bar") -> PureVar(5)))::Nil,
      heapConstraints = Map(FieldPtEdge(PureVar(1),"f") -> PureVar(2)),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),
      Map(PureVar(2) -> BoundedTypeSet(Some("Foo"), None,Set()),
        PureVar(5) -> BoundedTypeSet(Some("String"), None, Set())), AbstractTrace(Nil)),0)
    val prestates: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    prestates.foreach { prestate =>
      println(s"prestate: ${prestate}")
    }
    println(s"poststate: $post")

    val res = prestates.map(prestate => stSolver.simplify(prestate,esp))
    res.foreach{prestate =>
      println(s"simplified: ${prestate}")
    }
  }
  ignore("transfer over deserialized IR"){
    //TODO(Duke) finish this test
    val fooMethod = SerializedIRMethodLoc("fooClazz", "fooMethod()",Nil)
    val loc = (n:Int) => SerializedIRLineLoc(n,"",0)
    val loc1 = AppLoc(fooMethod, loc(0), true)
    val loc2 = AppLoc(fooMethod, loc(0), false)
    val loc3 = AppLoc(fooMethod, loc(1), true)
    val transitions:Map[AppLoc, Set[SerializedTransition]] = Map(
      loc1-> Set(CmdTransition(loc1, Goto(???,???,loc2))),
      loc2-> Set(NopTransition(loc3))
    )
    SerializedIR(transitions)
  }
  private val a = NamedPureVar("a")
  private val iFooA: OAbsMsg = AbsMsg(CBEnter, Set(Signature("", "foo()")), TopVal :: a :: Nil)
  ignore("Add matcher and phi abstraction when crossing callback entry") {
    val preloc = CallbackMethodInvoke(Signature("","foo()"), fooMethod) // Transition to just before foo is invoked
    val postloc = AppLoc(fooMethod,SerializedIRLineLoc(1, "",0), isPre=true)
    //val ir = new SerializedIR(Set(MethodTransition(preloc, postloc)))
    val ir = ???

    val lhs = AbsMsg(CBEnter, Set(Signature("", "bar()")), TopVal :: a :: Nil)
    //  I(cb a.bar()) <= I(cb a.foo())
    val spec = LSSpec(a::Nil, Nil,
      lhs,
      iFooA)
    val tr = new TransferFunctions(ir, new SpecSpace(Set(spec)),miniCha, true,FilterResolver(None))
//    val otheri = AbstractTrace(Once(CBExit, Set(("a","a")), "b"::Nil), Nil, Map())
    val otheri = ???
    val post = State(StateFormula(
      CallStackFrame(CallbackMethodReturn(Signature("","foo"),fooMethod, None), None, Map(StackVar("@this") -> recPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(),
      traceAbstraction = otheri,
      typeConstraints = Map()),
      nextAddr = 0)

    println(s"post: ${post.toString}")
    println(s"preloc: $preloc")
    println(s"postloc: $postloc")
    val prestate: Set[State] = tr.transfer(post,preloc, postloc)
    println(s"pre: ${prestate.toString}")
    assert(prestate.size == 1)
    val formula: AbstractTrace = prestate.head.traceAbstraction
    ???
//    assert(formula.modelVars.exists{
//      case (k,v) => k == a && v == recPv
//    }) //TODO: Stale test? I don't think we go from a cb inv to an app loc anymore
//    assert(formula.a == lhs)
//    assert(formula == otheri)
    val stack = prestate.head.callStack
    assert(stack.isEmpty)
  }
}
