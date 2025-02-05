package edu.colorado.plv.bounder.synthesis

import better.files.File
import edu.colorado.plv.bounder.BounderUtil
import edu.colorado.plv.bounder.BounderUtil.{Proven, Witnessed, interpretResult}
import edu.colorado.plv.bounder.ir.{CBEnter, CIEnter, CIExit, SootWrapper}
import edu.colorado.plv.bounder.lifestate.LifeState.{AbsMsg, And, AnyAbsMsg, LSAnyPred, LSSpec, NS, Not, OAbsMsg, Or, Signature}
import edu.colorado.plv.bounder.lifestate.RxJavaSpec.{Disposable_dispose, Maybe_create, Maybe_subscribeCi}
import edu.colorado.plv.bounder.lifestate.SAsyncTask.executeI
import edu.colorado.plv.bounder.lifestate.SDialog.dismissSignature
import edu.colorado.plv.bounder.lifestate.SpecSignatures.{Activity_onPause_entry, Activity_onPause_exit, Activity_onResume_entry, Button_init}
import edu.colorado.plv.bounder.lifestate.ViewSpec.{buttonEnabled, l, onClick, onClickI, setEnabled, setOnClickListenerI, setOnClickListenerINull}
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, LSPredAnyOrder, LifecycleSpec, RxJavaSpec, SAsyncTask, SDialog, SpecSignatures, SpecSpace, ViewSpec}
import edu.colorado.plv.bounder.solver.Z3StateSolver
import edu.colorado.plv.bounder.symbolicexecutor.ExperimentSpecs.row1Specs
import edu.colorado.plv.bounder.symbolicexecutor.{ExecutorConfig, LimitMaterializationApproxMode, PreciseApproxMode, QueryFinished}
import edu.colorado.plv.bounder.symbolicexecutor.state.{BoolVal, CallinReturnNonNull, DisallowedCallin, InitialQuery, MemoryOutputMode, NamedPureVar, NullVal, PrettyPrinting, Reachable, ReceiverNonNull, TopVal}
import edu.colorado.plv.bounder.synthesis.EnumModelGeneratorTest.{allReach, buttonEqReach, nullReach, onClickAfterOnCreateAndOnClick, onClickCanHappenNoPrev, onClickCanHappenTwice, onClickCanHappenWithPrev, onClickReachableNoSetEnable, onResumeFirstReach, queryOnActivityCreatedBeforeCall, queryOnClickAfterOnCreate, queryOnClickAfterOnResume, queryOnClickTwiceAfterReg, resumeFirstQ, resumeReachAfterPauseQ, resumeTwiceReachQ, row1, row1ActCreatedFirst, row1BugReach, row2, row4, row5, row6, row6Reach, srcReach, srcReachFrag}
import edu.colorado.plv.bounder.synthesis.SynthTestUtil.{cha, targetIze, toConcGraph, witTreeFromMsgList}
import edu.colorado.plv.bounder.testutils.MkApk
import edu.colorado.plv.bounder.testutils.MkApk.makeApkWithSources
import org.scalatest.funsuite.AnyFunSuite

object EnumModelGeneratorTest{
  /*
  Need to be careful about the contribution we claim.
  If we say this cuts down the search space, synthetic apps undercuts the argument that this works.
  First priority is still micro benchmarks
  Target: one "real" benchmark - just do as much "hacky" get it to work stuff as possible

  We set up a new problem where you only specify reachable and unreachable locations.
  We only want to synthesize the specs required for some given assertion.
  We develop a technique for this.

  Key technical idea: track the data dependency. = we should formalize this.

  High level idea: we can synthesize specs for reachable and unreachable.
  In comparison to related work: not just frequency patterns driving this, its the behavior of the analysis on the code.
  Can we synthesize something that is sound with respect to input.
   */
  val row1 = (destroyLine: String) =>
    s"""
       |package com.example.createdestroy;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |
       |import androidx.fragment.app.Fragment;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |
       |import rx.Single;
       |import rx.Subscription;
       |import rx.android.schedulers.AndroidSchedulers;
       |import rx.schedulers.Schedulers;
       |import rx.functions.Action1;
       |
       |
       |public class PlayerFragment extends Fragment {
       |    Subscription sub;
       |    //Callback with irrelevant subscribe
       |    @Override
       |    public View onCreateView(LayoutInflater inflater, ViewGroup container,
       |                             Bundle savedInstanceState) {
       |      return inflater.inflate(0, container, false);
       |    }
       |    @Override
       |    public void onCreate(Bundle savedInstanceState){
       |      super.onCreate(savedInstanceState);
       |    }
       |
       |    @Override
       |    public void onActivityCreated(Bundle savedInstanceState){
       |        super.onActivityCreated(savedInstanceState);
       |        sub = Single.create(subscriber -> {
       |            subscriber.onSuccess(3);
       |        }).subscribeOn(Schedulers.newThread())
       |        .observeOn(AndroidSchedulers.mainThread())
       |        .subscribe(new Action1<Object>(){
       |           @Override
       |           public void call(Object o){
       |             Activity act = getActivity(); //query1 : act != null
       |             act.toString();
       |           }
       |       });
       |    }
       |
       |    @Override
       |    public void onDestroy(){
       |        $destroyLine
       |    }
       |}
       |""".stripMargin

  val row2 = (cancelLine:String) =>
    s"""
       |package com.example.createdestroy;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |import android.os.AsyncTask;
       |import android.app.ProgressDialog;
       |
       |import android.app.Fragment;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |import android.view.View.OnClickListener;
       |
       |
       |
       |public class RemoverActivity extends Activity{
       |    FeedRemover remover = null;
       |    View button = null;
       |    @Override
       |    public void onCreate(Bundle b){
       |        remover = new FeedRemover();
       |        button = findViewById(3);
       |        button.setOnClickListener(new OnClickListener(){
       |            @Override
       |            public void onClick(View v){
       |                remover.execute();
       |                $cancelLine
       |            }
       |        });
       |    }
       |
       |
       |
       |    class FeedRemover extends AsyncTask<String, Void, String> {
       |		  @Override
       |		  protected void onPreExecute() {
       |		  }
       |
       |		  @Override
       |		  protected String doInBackground(String... params) {
       |			  return "";
       |		  }
       |
       |		  @Override
       |		  protected void onPostExecute(String result) {
       |        RemoverActivity.this.finish();
       |		  }
       |	  }
       |}
       |""".stripMargin
  val row5 =
    s"""
       |package com.example.createdestroy;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |import android.os.AsyncTask;
       |import android.app.ProgressDialog;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |
       |import rx.Single;
       |import rx.Subscription;
       |import rx.android.schedulers.AndroidSchedulers;
       |import rx.schedulers.Schedulers;
       |import rx.functions.Action1;
       |
       |
       |public class StatusActivity extends Activity{
       |    boolean resumed = false;
       |    @Override
       |    public void onResume(){
       |      PostTask p = new PostTask();
       |      p.execute();
       |      resumed = true;
       |    }
       |
       |
       |    @Override
       |    public void onPause(){
       |      resumed = false;
       |    }
       |    class PostTask extends AsyncTask<String, Void, String> {
       |		  private ProgressDialog progress;
       |
       |		  @Override
       |		  protected void onPreExecute() {
       |			  progress = ProgressDialog.show(StatusActivity.this, "Posting",
       |					"Please wait...");
       |			  progress.setCancelable(true);
       |		  }
       |
       |		  // Executes on a non-UI thread
       |		  @Override
       |		  protected String doInBackground(String... params) {
       |			  return "Successfully posted";
       |		  }
       |
       |		  @Override
       |		  protected void onPostExecute(String result) {
       |			  if(resumed){
       |				  progress.dismiss(); //query1
       |        }
       |		  }
       |	  }
       |}
       |""".stripMargin

  val row4 = (disableClick: String) =>
    s"""package com.example.createdestroy;
       |import android.app.Activity;
       |import android.os.Bundle;
       |import android.widget.Button;
       |import android.util.Log;
       |import android.view.View;
       |import android.os.Handler;
       |import android.view.View.OnClickListener;
       |
       |
       |public class MyActivity extends Activity {
       |    String s = null;
       |    View v = null;
       |    @Override
       |    protected void onCreate(Bundle b){
       |        v = new Button(this);
       |        v.setOnClickListener(new OnClickListener(){
       |           @Override
       |           public void onClick(View v){
       |             s.toString(); // query1
       |           }
       |        });
       |        (new Handler()).postDelayed(new Runnable(){
       |             @Override
       |             public void run(){
       |               MyActivity.this.finish();
       |               ${disableClick}
       |             }
       |        }, 3000);
       |    }
       |
       |    @Override
       |    protected void onResume() {
       |        s = "";
       |    }
       |
       |    @Override
       |    protected void onPause() {
       |        s = null;
       |    }
       |}""".stripMargin
//    s"""package com.example.createdestroy;
//       |import android.app.Activity;
//       |import android.os.Bundle;
//       |import android.util.Log;
//       |import android.view.View;
//       |import android.widget.Button;
//       |import android.os.Handler;
//       |import android.view.View.OnClickListener;
//       |
//       |
//       |public class MyActivity extends Activity {
//       |    String s = null;
//       |    View v = null; //TODO: move new button here and say similar to findview
//       |    @Override
//       |    protected void onResume(){
//       |        s = "";
//       |        //v = findViewById(3);
//       |        v = new Button(this);
//       |        v.setOnClickListener(new OnClickListener(){
//       |           @Override
//       |           public void onClick(View v){
//       |             s.toString(); // query1 null unreachable
//       |             MyActivity.this.finish();
//       |           }
//       |        });
//       |    }
//       |
//       |    @Override
//       |    protected void onPause() {
//       |        s = null;
//       |        ${???; disableClick} //TODO: make this closer to the original, over simplified now
//       |        //v.setOnClickListener(null);
//       |    }
//       |}""".stripMargin


  val row6 =
    s"""
       |package com.example.createdestroy;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |import android.os.AsyncTask;
       |import android.app.ProgressDialog;
       |
       |import android.app.Fragment;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |import android.view.View.OnClickListener;
       |import io.reactivex.disposables.Disposable;
       |import io.reactivex.schedulers.Schedulers;
       |import io.reactivex.android.schedulers.AndroidSchedulers;
       |import io.reactivex.Maybe;
       |
       |
       |public class ChaptersFragment extends Fragment {
       |  private Object controller;
       |  private Disposable disposable;
       |  @Override
       |  public void onStart() {
       |    super.onStart();
       |    if(disposable != null){
       |      disposable.dispose();
       |    }
       |
       |    controller = new Object();
       |
       |    disposable = Maybe.create(emitter -> {
       |      emitter.onSuccess(controller.toString()); //query1
       |    })
       |    .subscribeOn(Schedulers.io())
       |    .observeOn(AndroidSchedulers.mainThread())
       |    .subscribe(media -> Log.i("",""),
       |          error -> Log.e("",""));
       |  }
       |  public void onStop() {
       |    disposable.dispose();
       |    controller = null;
       |  }
       |}
       |""".stripMargin
  val row6Reach =
    s"""
       |package com.example.reach;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |import android.os.AsyncTask;
       |import android.app.ProgressDialog;
       |
       |import android.app.Fragment;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |import android.view.View.OnClickListener;
       |import io.reactivex.disposables.Disposable;
       |import io.reactivex.schedulers.Schedulers;
       |import io.reactivex.android.schedulers.AndroidSchedulers;
       |import io.reactivex.Maybe;
       |import io.reactivex.MaybeEmitter;
       |import io.reactivex.MaybeOnSubscribe;
       |
       |
       |public class ChaptersFragmentReach extends Fragment {
       |  private Object controller;
       |  private Disposable disposable;
       |  MaybeOnSubscribe listener = null;
       |  @Override
       |  public void onStart() {
       |    super.onStart();
       |    if(disposable != null){
       |      disposable.dispose();
       |    }
       |
       |    controller = new Object();
       |    listener = new MaybeOnSubscribe(){
       |       public void subscribe(MaybeEmitter emitter){
       |           if(listener==this){
       |              "".toString(); // reachable_with_last_create_registering
       |           }
       |           emitter.onSuccess(controller.toString()); //query1 37
       |       }
       |    };
       |    disposable = Maybe.create(listener)
       |    .subscribeOn(Schedulers.io())
       |    .observeOn(AndroidSchedulers.mainThread())
       |    .subscribe(media -> Log.i("",""),
       |          error -> Log.e("",""));
       |  }
       |  public void onStop() {
       |    disposable.dispose();
       |    controller = null;
       |  }
       |}
       |""".stripMargin
  val srcReachFrag =
    s"""
       |package com.example.reach;
       |import android.app.Activity;
       |import android.content.Context;
       |import android.net.Uri;
       |import android.os.Bundle;
       |
       |import androidx.fragment.app.Fragment;
       |
       |import android.util.Log;
       |import android.view.LayoutInflater;
       |import android.view.View;
       |import android.view.ViewGroup;
       |
       |import rx.Single;
       |import rx.Subscription;
       |import rx.android.schedulers.AndroidSchedulers;
       |import rx.schedulers.Schedulers;
       |import rx.functions.Action1;
       |
       |
       |public class PlayerFragmentReach extends Fragment {
       |    Subscription sub;
       |    Object createOrDestroyHappened=null;
       |    Object onActivityCreatedHappened=null;
       |    //Callback with irrelevant subscribe
       |    @Override
       |    public View onCreateView(LayoutInflater inflater, ViewGroup container,
       |                             Bundle savedInstanceState) {
       |      return inflater.inflate(0, container, false);
       |    }
       |    @Override
       |    public void onCreate(Bundle savedInstanceState){
       |      super.onCreate(savedInstanceState);
       |    }
       |
       |    @Override
       |    public void onActivityCreated(Bundle savedInstanceState){
       |        onActivityCreatedHappened=new Object();
       |        if(createOrDestroyHappened == null){
       |            "".toString(); //queryActCreatedFirst
       |        }
       |        createOrDestroyHappened = new Object();
       |        super.onActivityCreated(savedInstanceState);
       |        sub = Single.create(subscriber -> {
       |            subscriber.onSuccess(3);
       |        }).subscribeOn(Schedulers.newThread())
       |        .observeOn(AndroidSchedulers.mainThread())
       |        .subscribe(new Action1<Object>(){
       |            @Override
       |            public void call(Object o){
       |              if(onActivityCreatedHappened!=null){
       |                "".toString(); //queryOnActivityCreatedBeforeCall
       |              }
       |              Activity act = getActivity(); //queryReachFrag : act != null
       |              act.toString();
       |            }
       |        });
       |    }
       |
       |    @Override
       |    public void onDestroy(){
       |        createOrDestroyHappened = new Object();
       |    }
       |}
       |""".stripMargin



  val queryOnActivityCreatedBeforeCall_line = BounderUtil.lineForRegex(".*queryOnActivityCreatedBeforeCall.*".r, srcReachFrag)
  val queryOnActivityCreatedBeforeCall = Reachable(Signature("com.example.reach.PlayerFragmentReach$1",
    "void call(java.lang.Object)"),queryOnActivityCreatedBeforeCall_line)

  val row1ActCreatedFirst_line = BounderUtil.lineForRegex(".*queryActCreatedFirst.*".r, srcReachFrag)
  val row1ActCreatedFirst = Reachable(Signature("com.example.reach.PlayerFragmentReach",
    "void onActivityCreated(android.os.Bundle)"), row1ActCreatedFirst_line)

  val row1BugReach_line = BounderUtil.lineForRegex(".*queryReachFrag.*".r, srcReachFrag)
  val row1BugReach = CallinReturnNonNull(
    Signature("com.example.reach.PlayerFragment$1",
      "void call(java.lang.Object)"), row1BugReach_line ,
    ".*getActivity.*")
  val srcReach =
    s"""package com.example.reach;
       |import android.app.Activity;
       |import android.os.Bundle;
       |import android.util.Log;
       |import android.view.View;
       |import android.widget.Button;
       |import android.os.Handler;
       |import android.view.View.OnClickListener;
       |
       |
       |public class OtherActivity extends Activity implements OnClickListener {
       |    String s = "";
       |    View button = null;
       |    Object createResumedHappened = null;
       |    Object pausedHappened = null;
       |    Object resumeHappened = null;
       |    Object onCreateHappened = null;
       |    Object onClickHappened = null;
       |    Object onClickOnceAfterReg = null;
       |    @Override
       |    protected void onCreate(Bundle b){
       |        onCreateHappened = new Object();
       |        button = new Button(this);
       |        button.setOnClickListener(this);
       |        button.setEnabled(false);
       |        button.setEnabled(true);
       |
       |        Button button2 = new Button(this);
       |        OnClickListener listener = new OnClickListener(){
       |          @Override
       |          public void onClick(View v){
       |            "".toString(); // onClickReachableNoSetEnable
       |          }
       |        };
       |        button2.setOnClickListener(listener);
       |        button.setEnabled(true);
       |        button2.setOnClickListener(listener);
       |    }
       |    @Override
       |    protected void onResume(){
       |      if(createResumedHappened == null){
       |         "".toString(); //query4 reachable
       |      }
       |      if(pausedHappened != null){
       |        "".toString(); //query5 reachable
       |      }
       |      if(resumeHappened != null){
       |        "".toString(); // query6 reachable
       |      }
       |      if(pausedHappened == null){
       |       "".toString(); //query7 reachable
       |      }
       |      createResumedHappened = new Object();
       |      resumeHappened = new Object();
       |    }
       |    @Override
       |    public void onClick(View v){
       |      if(onCreateHappened != null && onClickHappened != null) {
       |        "".toString(); // onClickAfterOnCreateAndOnClick
       |      }
       |      if(onCreateHappened != null){
       |        "".toString(); // queryOnClickAfterOnCreate
       |      }
       |
       |      if(onClickOnceAfterReg != null){
       |        "queryOnClickTwiceAfterReg".toString(); // queryOnClickTwiceAfterReg
       |      }
       |
       |      if(onCreateHappened != null){
       |        "".toString(); // queryOnClickAfterOnResume
       |        onClickOnceAfterReg = new Object();
       |      }
       |
       |
       |      s.toString(); // query2 reachable
       |      OtherActivity.this.finish();
       |      if(v == button){
       |        s.toString(); //query3 reachable
       |      }
       |      if(onClickHappened != null){ // on click has happened
       |       "".toString(); // onClickCanHappenTwice
       |       if(onCreateHappened  != null){
       |        "onClickCanHapppenNoPrev".toString(); // onClickCanHappenNoPrev
       |       }
       |      }else{ //on click has not happened
       |         if(onCreateHappened  != null){
       |           "onClickCanHappenWithPrev".toString(); // onClickCanHappenWithPrev
       |         }
       |      }
       |
       |      onClickHappened = new Object();
       |    }
       |
       |    @Override
       |    protected void onPause() {
       |        s = null;
       |        createResumedHappened = new Object();
       |        pausedHappened = new Object();
       |    }
       |}""".stripMargin

  val onClickReach = Signature("com.example.reach.OtherActivity",
    "void onClick(android.view.View)")
  val line_reach = BounderUtil.lineForRegex(".*query2.*".r, srcReach)
  val nullReach = ReceiverNonNull(onClickReach, line_reach, Some(".*toString.*"))

  val onClickReachableNoSetEnable_line = BounderUtil.lineForRegex(".*onClickReachableNoSetEnable.*".r, srcReach)
  val onClickReachableNoSetEnable = Reachable(onClickReach.copy(base = onClickReach.base +"$1"),
    onClickReachableNoSetEnable_line)

  val onClickCanHappenTwice_line = BounderUtil.lineForRegex(".*onClickCanHappenTwice.*".r, srcReach)
  val onClickCanHappenTwice = Reachable(onClickReach, onClickCanHappenTwice_line)

  val onClickCanHappenNoPrev_line = BounderUtil.lineForRegex(".*onClickCanHappenNoPrev.*".r, srcReach)
  val onClickCanHappenNoPrev = Reachable(onClickReach, onClickCanHappenNoPrev_line)


  val onClickCanHappenWithPrev_line = BounderUtil.lineForRegex(".*onClickCanHappenWithPrev.*".r, srcReach)
  val onClickCanHappenWithPrev = Reachable(onClickReach, onClickCanHappenWithPrev_line)

  val queryOnClickTwiceAfterReg_line = BounderUtil.lineForRegex(".*queryOnClickTwiceAfterReg.*".r, srcReach)
  val queryOnClickTwiceAfterReg = Reachable(onClickReach, queryOnClickTwiceAfterReg_line)

  val queryOnClickAfterOnCreate_line = BounderUtil.lineForRegex(".*queryOnClickAfterOnCreate.*".r, srcReach)
  val queryOnClickAfterOnCreate = Reachable(onClickReach, queryOnClickAfterOnCreate_line)

  val onClickAfterOnCreateAndOnClick_line = BounderUtil.lineForRegex(".*queryOnClickAfterOnCreate.*".r, srcReach)
  val onClickAfterOnCreateAndOnClick = Reachable(onClickReach, onClickAfterOnCreateAndOnClick_line)

  val queryOnClickAfterOnResume_line = BounderUtil.lineForRegex(".*queryOnClickAfterOnResume.*".r, srcReach)
  val queryOnClickAfterOnResume = Reachable(onClickReach, queryOnClickAfterOnResume_line)

  val button_eq_reach = BounderUtil.lineForRegex(".*query3.*".r, srcReach)
  val buttonEqReach = Reachable(onClickReach, button_eq_reach)

  val onRes = onClickReach.copy(methodSignature = "void onResume()")
  val onResumeFirst_reach = BounderUtil.lineForRegex(".*query4.*".r, srcReach)
  val onResumeFirstReach =
    Reachable(onRes, onResumeFirst_reach)

  val resumeReachAfterPause = BounderUtil.lineForRegex(".*query5.*".r, srcReach)
  val resumeReachAfterPauseQ =
    Reachable(onRes, resumeReachAfterPause)


  val resumeTwiceReach = BounderUtil.lineForRegex(".*query6.*".r, srcReach)
  val resumeTwiceReachQ =
    Reachable(onRes, resumeTwiceReach)

  val resumeFirst = BounderUtil.lineForRegex(".*query7.*".r, srcReach)
  val resumeFirstQ = Reachable(onRes, resumeFirst)

  val allReach = List[InitialQuery](
    buttonEqReach,
    nullReach,
    onResumeFirstReach,
    queryOnActivityCreatedBeforeCall,
    queryOnClickAfterOnCreate,
    resumeFirstQ,
    resumeReachAfterPauseQ,
    resumeTwiceReachQ,
    row1ActCreatedFirst,
    onClickCanHappenTwice,
    onClickAfterOnCreateAndOnClick,
    queryOnClickTwiceAfterReg,
    onClickCanHappenWithPrev,
    onClickCanHappenNoPrev
  )
}
class EnumModelGeneratorTest extends AnyFunSuite {
  val DUMP_DBG = false //Uncomment to skip writing out paths from historia

  val a = NamedPureVar("a")
  val f = NamedPureVar("f")
  val l = NamedPureVar("l")
  val s = NamedPureVar("s")
  val t = NamedPureVar("t")
  val v = NamedPureVar("v")
  val a_onCreate = SpecSignatures.Activity_onCreate_entry
  val a_onDestroy = SpecSignatures.Activity_onDestroy_exit
  val s_a_subscribe = SpecSignatures.RxJava_subscribe_exit.copy(lsVars = s::TopVal::a::Nil)
  val s_unsubscribe = SpecSignatures.RxJava_unsubscribe_exit
  val t_create = SpecSignatures.RxJava_create_exit
  val a_call = SpecSignatures.RxJava_call_entry.copy(lsVars = TopVal::a::Nil)

  ignore("Encode Node Reachability motivating example - ConcGraph"){
    implicit val ord = new DummyOrd
    //TODO: may need to declare vars distinct
    val unreachSeq = toConcGraph(
      targetIze(List(a_onCreate, t_create, s_a_subscribe,a_onDestroy, s_unsubscribe, a_call)))
    val reachSeq = toConcGraph(
      targetIze(List(a_onCreate, t_create, s_a_subscribe,a_onDestroy, a_call)))
    val gen = new EnumModelGenerator(???,???,???,???)
    val spec = new SpecSpace(Set(
      LSSpec(a::Nil,Nil,  LSAnyPred , a_call)
    ), matcherSpace = Set())
//    implicit val solver = new Z3StateSolver(cha)
//    val res = gen.learnRulesFromConcGraph(Set(unreachSeq), Set(reachSeq), spec)
    ???



  }
  ignore("Encode Node Reachability motivating example - witTree"){
    implicit val ord = new DummyOrd
    implicit val outputMode = MemoryOutputMode
    //TODO: may need to declare vars distinct
    val unreachSeq = SynthTestUtil.witTreeFromMsgList(
      targetIze(List(a_onCreate, t_create, s_a_subscribe,a_onDestroy, s_unsubscribe, a_call)))
    val reachSeq = witTreeFromMsgList(
      targetIze(List(a_onCreate, t_create, s_a_subscribe,a_onDestroy, a_call)))
    val gen = new EnumModelGenerator(???,???,???,???)
    val spec = new SpecSpace(Set(
      LSSpec(a::Nil,Nil,  LSAnyPred , a_call)
    ), matcherSpace = Set())
//    val res = gen.learnRulesFromExamples(unreachSeq, reachSeq, spec)
    ???



  }
  ignore("Specification enumeration"){
    val hi:LSSpec = LSSpec(l::v:: Nil, Nil, LSAnyPred, onClickI)
    val startingSpec = Set(hi)
    val iSet = Set(onClickI, setOnClickListenerI, setOnClickListenerINull,
      Activity_onResume_entry, Activity_onPause_exit)

    val specSpace = new SpecSpace(startingSpec, matcherSpace = iSet)
    val test: String => Unit = apk => {
      val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)

      implicit val dbMode = MemoryOutputMode
      val config = ExecutorConfig(
        stepLimit = 2000, w, specSpace,
        component = Some(List("com.example.createdestroy.(MyActivity|OtherActivity)")),
        outputMode = dbMode, timeLimit = 30)

      val line = BounderUtil.lineForRegex(".*query1.*".r, row4("v.setOnClickListener(null);"))
      val clickSignature = Signature("com.example.createdestroy.MyActivity$1",
        "void onClick(android.view.View)")
      val nullUnreach = ReceiverNonNull(clickSignature, line, Some(".*toString.*"))

      //val firstClickCbReach = Reachable(clickSignature, line)


      val onClickReach = Signature("com.example.createdestroy.OtherActivity",
        "void onClick(android.view.View)")
      val line_reach = BounderUtil.lineForRegex(".*query2.*".r, srcReach)
      val nullReach = ReceiverNonNull(onClickReach, line_reach, Some(".*toString.*"))

      val button_eq_reach = BounderUtil.lineForRegex(".*query3.*".r, srcReach)
      val buttonEqReach = Reachable(onClickReach, button_eq_reach)

      val onResumeFirst_reach = BounderUtil.lineForRegex(".*query4.*".r, srcReach)
      val onResumeFirstReach =
        Reachable(onClickReach.copy(methodSignature = "void onResume()"), onResumeFirst_reach)

      val gen = new EnumModelGenerator(nullUnreach, Set(nullReach, buttonEqReach, onResumeFirstReach /*firstClickCbReach*/), specSpace, config)
      var i = 0
      var c = List(hi)
      while(i < 10){
        c = gen.stepSpec(c.head, Map(), ???, enableOptimizations = true)._1
        println(c)
        //TODO:
      }
    }
    makeApkWithSources(Map("MyActivity.java" -> row4("v.setOnClickListener(null);"),
      "OtherActivity.java" -> srcReach), MkApk.RXBase,
      test)


  }

  test("Synthesis Row 1: Antennapod getActivity returns null fix") {
    println("starting Row 1")

    val row1Src = row1("sub.unsubscribe();")
    val startingSpec = Set[LSSpec](
//      LifecycleSpec.Fragment_activityCreatedOnlyFirst.copy(pred=LSAnyPred),
//      RxJavaSpec.call.copy(pred = NS(
//        SpecSignatures.RxJava_subscribe_exit,
//        AnyAbsMsg)),
//      FragmentGetActivityNullSpec.getActivityNull
      LSSpec(l::Nil, Nil, LSAnyPred, SpecSignatures.RxJava_call_entry),
      LSSpec(f :: Nil, Nil,
        LSAnyPred,
        SpecSignatures.Fragment_onActivityCreated_entry),
      FragmentGetActivityNullSpec.getActivityNull
    )

    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

        val iSet = Set(
          SpecSignatures.Fragment_onDestroy_exit,
          SpecSignatures.Fragment_onActivityCreated_entry,
          SpecSignatures.RxJava_call_entry,
          SpecSignatures.RxJava_unsubscribe_exit,
          SpecSignatures.RxJava_subscribe_exit,
        )

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.*")),
          outputMode = dbMode, timeLimit = 30)

        val line = BounderUtil.lineForRegex(".*query1.*".r, row1Src)


        val query = CallinReturnNonNull(
          Signature("com.example.createdestroy.PlayerFragment$1",
            "void call(java.lang.Object)"), line,
          ".*getActivity.*")

        val queryLocReach = Reachable(query.sig, query.line)


        val reachLoc = Set[InitialQuery](queryLocReach, nullReach, buttonEqReach, onResumeFirstReach,
          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, row1ActCreatedFirst, queryOnActivityCreatedBeforeCall)


        val gen = new EnumModelGenerator(query, reachLoc, specSpace, config)
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 1")
            println("=====================")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")
            if (DUMP_DBG)
              PrettyPrinting.dumpSpec(space, "cbSpec")
            println("\nstats for starting spec row 1")
            println("---------------------")
            println(gen.initialSpecWithStats.stats().map{r => s"${r._1} : ${r._2}\n"})
            println("\nstats for final spec row 1")
            println("---------------------")
            println(space.stats().map{r => s"${r._1} : ${r._2}\n"})
            println("\nruntime stats row 1")
            println("---------------------")
            println(gen.getStats().map{r => s"${r._1} : ${r._2}\n"})

          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("PlayerFragment.java" -> row1Src, "OtherActivity.java" -> srcReach,
      "PlayerFragmentReach.java" -> srcReachFrag), MkApk.RXBase,
      test)
  }
  //TODO: === would be nice to show bugs time out or reach contradicton
  ignore("Synthesis Row 1: Antennapod getActivity returns null bug") {

    val row1Src = row1("")
    val startingSpec = Set[LSSpec](
      //      LifecycleSpec.Fragment_activityCreatedOnlyFirst.copy(pred=LSAnyPred),
      //      RxJavaSpec.call.copy(pred = NS(
      //        SpecSignatures.RxJava_subscribe_exit,
      //        AnyAbsMsg)),
      //      FragmentGetActivityNullSpec.getActivityNull
      LSSpec(l :: Nil, Nil, LSAnyPred, SpecSignatures.RxJava_call_entry),
      LSSpec(f :: Nil, Nil,
        LSAnyPred,
        SpecSignatures.Fragment_onActivityCreated_entry),
      FragmentGetActivityNullSpec.getActivityNull
    )

    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

        val iSet = Set(
          SpecSignatures.Fragment_onDestroy_exit,
          SpecSignatures.Fragment_onActivityCreated_entry,
          SpecSignatures.RxJava_call_entry,
          SpecSignatures.RxJava_unsubscribe_exit,
          SpecSignatures.RxJava_subscribe_exit,
        )

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.*")),
          outputMode = dbMode, timeLimit = 30)

        val line = BounderUtil.lineForRegex(".*query1.*".r, row1Src)


        val query = CallinReturnNonNull(
          Signature("com.example.createdestroy.PlayerFragment$1",
            "void call(java.lang.Object)"), line,
          ".*getActivity.*")

        val queryLocReach = Reachable(query.sig, query.line)


        val reachLoc = Set[InitialQuery](queryLocReach, nullReach, buttonEqReach, onResumeFirstReach,
          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, row1ActCreatedFirst, queryOnActivityCreatedBeforeCall)


        val gen = new EnumModelGenerator(query, reachLoc, specSpace, config)
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 1")
            println("-------------------")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")
            if (DUMP_DBG)
              PrettyPrinting.dumpSpec(space, "cbSpec")
          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("PlayerFragment.java" -> row1Src, "OtherActivity.java" -> srcReach,
      "PlayerFragmentReach.java" -> srcReachFrag), MkApk.RXBase,
      test)
  }
  test("Synthesis Row 2: Antennapod execute") {
    println("starting Row 2")

    val msgSetEnabledFalse = AbsMsg(CIExit, setEnabled, TopVal :: v :: BoolVal(false) :: Nil)
    val msgSetEnabledTrue = AbsMsg(CIExit, setEnabled, TopVal :: v :: BoolVal(true) :: Nil)
    val row2Src = row2("button.setEnabled(false);")
    val startingSpec = Set[LSSpec]( //TODO==== partially filled out, perhaps back off when we figure out what is wrong
      ViewSpec.clickWhileNotDisabled.copy(
        pred = And(setOnClickListenerI, LSAnyPred), existQuant = v::Nil),
//          Not(AbsMsg(CIExit, setEnabled, TopVal::v::BoolVal(false)::Nil)),
//          NS(AbsMsg(CIExit, setEnabled, TopVal::v::BoolVal(true)::Nil), AnyAbsMsg))), existQuant= v::Nil),
//      ViewSpec.clickWhileNotDisabled.copy(pred = LSAnyPred,existQuant = Nil),
//        And(setOnClickListenerI.copy(lsVars = List(TopVal, v, l)),
//          Or(Not(AbsMsg(CIExit, setEnabled, TopVal::v::BoolVal(false)::Nil)),LSAnyPred) ),
//        existQuant = v::Nil),
      LifecycleSpec.Activity_createdOnlyFirst //.copy(pred=LSAnyPred) //TODO======
    )

    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

        val iSet = Set(
//          setOnClickListenerI,
          msgSetEnabledTrue, msgSetEnabledFalse
//          SpecSignatures.Activity_onCreate_entry,
//          executeI
        )

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, Set(SAsyncTask.disallowDoubleExecute), matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.*")),
          outputMode = dbMode, timeLimit = 60, z3InstanceLimit = 3)

        val query = DisallowedCallin(
          "com.example.createdestroy.RemoverActivity$1",
          "void onClick(android.view.View)",
          SAsyncTask.disallowDoubleExecute)

        val reachablelocs :Set[InitialQuery] = Set(
//          nullReach, buttonEqReach, onResumeFirstReach, onClickCanHappenNoPrev, resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, queryOnClickAfterOnCreate,
          onClickCanHappenTwice, onClickReachableNoSetEnable, onClickAfterOnCreateAndOnClick, onClickCanHappenWithPrev,
          queryOnClickTwiceAfterReg)
        val gen = new EnumModelGenerator(query, reachablelocs, specSpace, config)

        //Unused: queryOnClickAfterOnCreate
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 2")
            println("-------------------")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")
            if (DUMP_DBG)
              PrettyPrinting.dumpSpec(space, "cbSpec")

            println("\nstats for starting spec row 2")
            println("---------------------")
            println(gen.initialSpecWithStats.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nstats for final spec row 2")
            println("---------------------")
            println(space.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nruntime stats row 2")
            println("---------------------")
            println(gen.getStats().map { r => s"${r._1} : ${r._2}\n" })
          //TODO: should implement auto check for synth specs
          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("RemoverActivity.java" -> row2Src, "OtherActivity.java" -> srcReach,
      "PlayerFragmentReach.java" -> srcReachFrag), MkApk.RXBase,
      test)
  }

  test("Synthesis Row 3: Antennapod dismiss") {
    println("starting Row 3")
    val startingSpec = Set[LSSpec](
      SDialog.noDupeShow.copy(pred = LSAnyPred)
    )
//    ???
    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

        val d = NamedPureVar("d")
        val iSet = Set[OAbsMsg](
          SDialog.showI2,
          AbsMsg(CIEnter, dismissSignature, TopVal::d::Nil),
          SpecSignatures.Activity_onResume_entry,
          SpecSignatures.Activity_onPause_exit
        )

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, Set(SDialog.disallowDismiss), matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.*")),
          outputMode = dbMode, timeLimit = 1800, z3InstanceLimit = 3)


        val query = DisallowedCallin(
          "com.example.createdestroy.StatusActivity$PostTask",
          "void onPostExecute(java.lang.String)",
          SDialog.disallowDismiss)

        // TODO: Set(nullReach, buttonEqReach, onResumeFirstReach,
        //          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, queryOnClickAfterOnCreate,
        //          onClickCanHappenTwice, onClickReachableNoSetEnable, onClickAfterOnCreateAndOnClick)
        //TODO: remove one at a time and figure out smallest set needed for the evaluation
        val reachSet = Set[InitialQuery](nullReach, buttonEqReach, onResumeFirstReach,
          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, queryOnClickAfterOnCreate,
          onClickCanHappenTwice, onClickReachableNoSetEnable, onClickAfterOnCreateAndOnClick)
        val gen = new EnumModelGenerator(query, reachSet, specSpace, config,
          reachPkgFilter = List("com.example.reach.*"), unreachPkgFilter = List("com.example.createdestroy.*") )

        //Unused: queryOnClickAfterOnCreate
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 3")
            println("-------------------")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")
            val newConfig = config.copy(specSpace = space)
            if (DUMP_DBG)
              PrettyPrinting.dumpSpec(space, "cbSpec")

            println("\nstats for starting spec row 3")
            println("---------------------")
            println(gen.initialSpecWithStats.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nstats for final spec row 3")
            println("---------------------")
            println(space.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nruntime stats row 3")
            println("---------------------")
            println(gen.getStats().map { r => s"${r._1} : ${r._2}\n" })
          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("StatusActivity.java" -> row5, "OtherActivity.java" -> srcReach,
      "PlayerFragmentReach.java" -> srcReachFrag), MkApk.RXBase,
      test)
  }
  test("Synthesis Row 4: simplification of Connect bot click/finish") {
    println("starting Row 4")
    //TODO:==== accidentally swapped out with simplified version, get working on full version
    //Or(NS(SpecSignatures.Activity_onPause_exit, SpecSignatures.Activity_onResume_entry),
    //          Not(SpecSignatures.Activity_onResume_entry))
    //TODO: try replacing v in template for _
    val startingSpec = Set[LSSpec](
      LSSpec(a :: Nil, Nil, LSAnyPred, SpecSignatures.Activity_onResume_entry),
      LSSpec(l::v:: Nil, Nil, LSAnyPred, onClickI)
    )

    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

        val iSet = Set(onClickI, setOnClickListenerI, setOnClickListenerINull,
          Activity_onResume_entry, Activity_onPause_exit)

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.(MyActivity|OtherActivity)")),
          outputMode = dbMode, timeLimit = 30)

        val line = BounderUtil.lineForRegex(".*query1.*".r, row4("v.setOnClickListener(null);"))
        val clickSignature = Signature("com.example.createdestroy.MyActivity$1",
          "void onClick(android.view.View)")
        val nullUnreach = ReceiverNonNull(clickSignature, line, Some(".*toString.*"))



        val gen = new EnumModelGenerator(nullUnreach,Set(nullReach, buttonEqReach, onResumeFirstReach,
          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ , queryOnClickAfterOnCreate), specSpace, config) //
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 4")
            println("-------------------")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")
            assert(space.getSpecs.forall{spec => //TODO:dbg code ====
              gen.connectedSpec(spec)
            })
            if(DUMP_DBG)
              PrettyPrinting.dumpSpec(space, "cbSpec")

            println("\nstats for starting spec row 4")
            println("---------------------")
            println(gen.initialSpecWithStats.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nstats for final spec row 4")
            println("---------------------")
            println(space.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nruntime stats")
            println("---------------------")
            println(gen.getStats().map { r => s"${r._1} : ${r._2}\n" })
          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("MyActivity.java" -> row4("v.setOnClickListener(null);"), "OtherActivity.java" -> srcReach), MkApk.RXBase,
      test)
  }
  test("Synthesis Row 5: synch null free") {

    println("starting Row 5")
    val startingSpec = Set[LSSpec](
//      RxJavaSpec.subscribeSpec.copy(pred = LSAnyPred),
      // start from knowing registration method then figure out subscribe/dispose
      RxJavaSpec.subscribeSpec.copy(pred= And(Maybe_create, LSAnyPred)),
      RxJavaSpec.Maybe_subscribeOn,
      RxJavaSpec.Maybe_observeOn,
      LifecycleSpec.startStopAlternation,
      //LifecycleSpec.stopStartAlternation,
      RxJavaSpec.Maybe_create_unique
    )

    val test: String => Unit = apk => {
      File.usingTemporaryDirectory() { tmpDir =>
        assert(apk != null)
        // val dbFile = tmpDir / "paths.db"
        // println(dbFile)
        // implicit val dbMode = DBOutputMode(dbFile.toString, truncate = false)
        // dbMode.startMeta()
        implicit val dbMode = MemoryOutputMode

//        val iSet = startingSpec.flatMap{r =>r.pred.allMsg ++ }
        val iSet = Set(Maybe_subscribeCi, Disposable_dispose, Maybe_create)

        val w = new SootWrapper(apk, toOverride = startingSpec ++ iSet)
        //val dbg = w.dumpDebug("com.example")

        val specSpace = new SpecSpace(startingSpec, Set(SDialog.disallowDismiss), matcherSpace = iSet)
        val config = ExecutorConfig(
          stepLimit = 2000, w, specSpace,
          component = Some(List("com.example.createdestroy.*")),
          outputMode = dbMode, timeLimit = 200, z3InstanceLimit = 3)


        val line = BounderUtil.lineForRegex(".*query1.*".r,row6)

        val querySig = Signature("com.example.createdestroy.ChaptersFragment",
          "void lambda$onStart$0$ChaptersFragment(io.reactivex.MaybeEmitter)")
        val query = ReceiverNonNull(
          querySig,
          line, Some(".*toString.*"))

        val lineReach = BounderUtil.lineForRegex(".*query1.*".r,row6Reach)
        val querySigReach = Signature("com.example.reach.ChaptersFragmentReach$1",
          "void subscribe(io.reactivex.MaybeEmitter)")
        val queryReach = Reachable(querySigReach, lineReach)

        val lineReachReg = BounderUtil.lineForRegex(".*reachable_with_last_create_registering.*".r, row6Reach)
        val queryReachReg = Reachable(querySigReach,lineReachReg)


        // TODO: Set(nullReach, buttonEqReach, onResumeFirstReach,
        //          resumeReachAfterPauseQ, resumeTwiceReachQ, resumeFirstQ, queryOnClickAfterOnCreate,
        //          onClickCanHappenTwice, onClickReachableNoSetEnable, onClickAfterOnCreateAndOnClick)
        //TODO: remove one at a time and figure out smallest set needed for the evaluation
        val gen = new EnumModelGenerator(query,
          reachable = Set(
            queryReach,
            queryReachReg),
          specSpace, config,reachPkgFilter = List(".*com.example.reach.*"),
          unreachPkgFilter = List(".*com.example.createdestroy.*"))

        //Unused: queryOnClickAfterOnCreate
        val res = gen.run()
        res match {
          case LearnSuccess(space) =>
            println("final specification Row 5")
            println("-------------------")
            val spaceStr = space.toString
            println(spaceStr)
            println("dumping debug info")

            println("\nstats for starting spec row 5")
            println("---------------------")
            println(gen.initialSpecWithStats.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nstats for final spec row 5")
            println("---------------------")
            println(space.stats().map { r => s"${r._1} : ${r._2}\n" })
            println("\nruntime stats")
            println("---------------------")
            println(gen.getStats().map { r => s"${r._1} : ${r._2}\n" })
          case LearnFailure => throw new IllegalStateException("failed to learn a sufficient spec")
        }
      }
    }
    makeApkWithSources(Map("ChaptersFragment.java" -> row6, "OtherActivity.java" -> srcReach,
      "PlayerFragmentReach.java" -> srcReachFrag, "ChaptersFragmentReach.java" -> row6Reach), MkApk.RXBoth,
      test)
  }


}
