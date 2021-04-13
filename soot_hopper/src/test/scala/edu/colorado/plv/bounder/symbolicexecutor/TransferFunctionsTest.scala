package edu.colorado.plv.bounder.symbolicexecutor

import better.files.Resource
import edu.colorado.plv.bounder.ir.{TestIR, _}
import edu.colorado.plv.bounder.lifestate.LifeState.{I, LSSpec, SetSignatureMatcher, SignatureMatcher}
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.solver.{ClassHierarchyConstraints, SetInclusionTypeSolving, SolverTypeSolving, StateTypeSolving, Z3StateSolver}
import edu.colorado.plv.bounder.symbolicexecutor.state._
import org.scalatest.funsuite.AnyFunSuite
import com.microsoft.z3.Context
import upickle.default.read

class TransferFunctionsTest extends AnyFunSuite {
  val ctx = new Context
  val solver = ctx.mkSolver()
  val hierarchy: Map[String, Set[String]] =
    Map("java.lang.Object" -> Set("String", "Foo", "Bar", "java.lang.Object"),
      "String" -> Set("String"), "Foo" -> Set("Bar", "Foo"), "Bar" -> Set("Bar"))
  private def getStateSolver(stateTypeSolving: StateTypeSolving = SolverTypeSolving):(Z3StateSolver, ClassHierarchyConstraints) = {
    val pc = new ClassHierarchyConstraints(hierarchy,Set(), stateTypeSolving)
    (new Z3StateSolver(pc),pc)
  }

  implicit def set2SigMat(s:Set[(String,String)]):SignatureMatcher = SetSignatureMatcher(s)

  val miniCha = new ClassHierarchyConstraints(hierarchy, Set("java.lang.Runnable"))
  val tr = (ir:TestIR, cha:ClassHierarchyConstraints) => new TransferFunctions(ir, new SpecSpace(Set()),cha)
  def testCmdTransfer(cmd:AppLoc => CmdWrapper, post:State, testIRMethod: TestIRMethodLoc):Set[State] = {
    val preloc = AppLoc(testIRMethod,TestIRLineLoc(1), isPre=true)
    val postloc = AppLoc(testIRMethod,TestIRLineLoc(1), isPre=false)
    val ir = new TestIR(Set(CmdTransition(preloc, cmd(postloc), postloc)))
    tr(ir,miniCha).transfer(post,preloc, postloc)
  }

  def transferJsonTest(name:String,spec:SpecSpace):Set[State] = {

    val js = ujson.Value(Resource.getAsString(name)).obj
    val state = read[State](js("state"))
    val source = read[Loc](js("source"))
    val target: Loc = read[Loc](js("target"))
    val cmd = read[CmdWrapper](js("cmd"))
    val cha = read[ClassHierarchyConstraints](Resource.getAsString("TestStates/hierarchy2.json"))

    val ir = new TestIR(Set(CmdTransition(source,cmd,target)))
    val transfer = new TransferFunctions(ir, spec,cha)
    val out = transfer.transfer(state, target, source)
    out
  }

  ignore("Callin return test"){
    val spec = new SpecSpace(Set(FragmentGetActivityNullSpec.getActivityNull,
      RxJavaSpec.call
    ))
    val res = transferJsonTest("TestStates/post_unsubscribe_callin.json",spec)
    ??? //TODO:
  }
  //Test transfer function where field is assigned and base may or may not be aliased
  // pre: this -> a^ * b^.out -> b1^ /\ b1^ == null
  // post: (this -> a^ * a^.out -> c^* d^.out -> e^) OR (this -> a^ * a^.out -> c^ AND a^=d^ AND c^=d^)
  val fooMethod = TestIRMethodLoc("","foo", List(Some(LocalWrapper("@this","java.lang.Object"))))
  val fooMethodReturn = CallbackMethodReturn("", "foo", fooMethod, None)
  test("Transfer may or may not alias") {
    val cmd = (loc:AppLoc) => {
      val thisLocal = LocalWrapper("@this", "java.lang.Object")
      AssignCmd(FieldReference(thisLocal, "Object", "Object", "o"), NullConst, loc)
    }
    val basePv = PureVar(State.getId_TESTONLY())
    val otherPv = PureVar(State.getId_TESTONLY())
    val post = State(CallStackFrame(fooMethodReturn, None, Map(StackVar("@this") -> basePv))::Nil,
      heapConstraints = Map(FieldPtEdge(otherPv, "o") -> NullVal),
      pureFormula = Set(),
      traceAbstraction = Set(),
      typeConstraints = Map(),
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
    val basePv = PureVar(State.getId_TESTONLY())
    val otherPv = PureVar(State.getId_TESTONLY())
    val post = State(CallStackFrame(fooMethodReturn, None, Map(StackVar("@this") -> basePv))::Nil,
      heapConstraints = Map(FieldPtEdge(basePv, "o") -> otherPv),
      pureFormula = Set(),
      traceAbstraction = Set(),
      typeConstraints = Map(),
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
    val nullPv = PureVar(State.getId_TESTONLY())
    val post = State(
      CallStackFrame(fooMethodReturn, None, Map(StackVar("bar") -> nullPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),Map(), Set(),0)
    val prestate: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    println(s"poststate: $post")
    println(s"prestate: $prestate")
    assert(prestate.size == 1)
    val formula = prestate.head.pureFormula
    assert(formula.contains(PureConstraint(nullPv,Equals, NullVal)))
    assert(formula.contains(PureConstraint(nullPv,NotEquals, NullVal)))
  }
  test("Transfer assign local local") {
    val cmd= (loc:AppLoc) => AssignCmd(LocalWrapper("bar","Object"),LocalWrapper("baz","String"),loc)
    val nullPv = PureVar(State.getId_TESTONLY())
    val post = State(
      CallStackFrame(CallbackMethodReturn("","foo",fooMethod, None), None, Map(StackVar("bar") -> nullPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),Map(), Set(),0)
    val prestate: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    println(s"poststate: $post")
    println(s"prestate: ${prestate}")
    assert(prestate.size == 1)
    val formula = prestate.head.pureFormula
    assert(formula.contains(PureConstraint(nullPv,Equals, NullVal)))
    assert(prestate.head.callStack.head.locals.contains(StackVar("baz")))
    assert(!prestate.head.callStack.head.locals.contains(StackVar("bar")))
  }
  test("Transfer assign from materialized heap cell") {
    implicit val (stSolver, cha) = getStateSolver()
    val x = LocalWrapper("x","Object")
    // bar := x.f
    // post{x -> v-4 bar -> p-5 * v-1.f -> v-2}
    val cmd= (loc:AppLoc) => AssignCmd(LocalWrapper("bar","Object"),FieldReference(x,"Object","Object","f"),loc)
    val nullPv = PureVar(State.getId_TESTONLY())
    val post = State(
      CallStackFrame(CallbackMethodReturn("","foo",fooMethod, None), None,
        Map(StackVar("x") -> PureVar(4), StackVar("bar") -> PureVar(5)))::Nil,
      heapConstraints = Map(FieldPtEdge(PureVar(1),"f") -> PureVar(2)),
      pureFormula = Set(PureConstraint(nullPv,Equals, NullVal)),
      Map(PureVar(2) -> BoundedTypeSet(Some("Foo"), None,Set()),
        PureVar(5) -> BoundedTypeSet(Some("String"), None, Set())), Set(),0)
    val prestates: Set[State] = testCmdTransfer(cmd, post,fooMethod)
    prestates.foreach { prestate =>
      println(s"prestate: ${prestate}")
    }
    println(s"poststate: $post")

    val res = prestates.map(prestate => stSolver.simplify(prestate))
    res.foreach{prestate =>
      println(s"simplified: ${prestate}")
    }
    assert(res.size == 2)
    assert(res.exists(v => v.isEmpty))
    assert(res.exists{v => v.isDefined && v.get.heapConstraints.size == 2 && v.get.testGet(x) == Some(PureVar(4))})
  }
  private val iFooA: I = I(CBEnter, Set(("", "foo")), "_" :: "a" :: Nil)
  test("Add matcher and phi abstraction when crossing callback entry") {
    val preloc = CallbackMethodInvoke("","foo", fooMethod) // Transition to just before foo is invoked
    val postloc = AppLoc(fooMethod,TestIRLineLoc(1), isPre=true)
    val ir = new TestIR(Set(MethodTransition(preloc, postloc)))
    val lhs = I(CBEnter, Set(("", "bar")), "_" :: "a" :: Nil)
    //  I(cb a.bar()) <= I(cb a.foo())
    val spec = LSSpec(
      lhs,
      iFooA)
    val tr = new TransferFunctions(ir, new SpecSpace(Set(spec)),miniCha)
    val recPv = PureVar(State.getId_TESTONLY())
    val otheri = AbstractTrace(I(CBExit, Set(("a","a")), "b"::Nil), Nil, Map())
    val post = State(
      CallStackFrame(CallbackMethodReturn("","foo",fooMethod, None), None, Map(StackVar("@this") -> recPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(),
      traceAbstraction = Set(otheri),
      typeConstraints = Map(),
      nextAddr = 0)

    println(s"post: ${post.toString}")
    println(s"preloc: $preloc")
    println(s"postloc: $postloc")
    val prestate: Set[State] = tr.transfer(post,preloc, postloc)
    println(s"pre: ${prestate.toString}")
    assert(prestate.size == 1)
    val formula: Set[AbstractTrace] = prestate.head.traceAbstraction
    assert(formula.exists(p => p.modelVars.exists{
      case (k,v) => k == "a" && v == recPv
    })) //TODO: Stale test? I don't think we go from a cb inv to an app loc anymore
    assert(formula.exists(p => p.a == lhs))
    assert(formula.contains(otheri))
    val stack = prestate.head.callStack
    assert(stack.isEmpty)
  }
  test("Discharge I(m1^) phi abstraction when post state must generate m1^ for previous transition") {
    val preloc = CallbackMethodInvoke("","foo", fooMethod) // Transition to just before foo is invoked
    val postloc = AppLoc(fooMethod,TestIRLineLoc(1), isPre=true)
    val ir = new TestIR(Set(MethodTransition(preloc, postloc)))
    val trf = tr(ir,miniCha)
    val recPv = PureVar(State.getId_TESTONLY())
    val post = State(
      CallStackFrame(CallbackMethodReturn("","foo",fooMethod, None), None, Map(StackVar("@this") -> recPv))::Nil,
      heapConstraints = Map(),
      pureFormula = Set(),
      typeConstraints = Map(),
      traceAbstraction = Set(AbstractTrace(iFooA, Nil, Map("a"->recPv))),nextAddr = 0)
    println(s"post: ${post.toString}")
    val prestate: Set[State] = trf.transfer(post,preloc, postloc)
    println(s"pre: ${prestate.toString}")
    val formula = prestate.head.traceAbstraction
    assert(formula.exists(p => p.modelVars.exists{
      case (k,v) => k == "a" && v == recPv
    }))
  }
}
