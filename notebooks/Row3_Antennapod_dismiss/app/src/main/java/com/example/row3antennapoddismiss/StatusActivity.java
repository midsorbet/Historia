package com.example.row3antennapoddismiss;

import androidx.appcompat.app.AppCompatActivity;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.app.ProgressDialog;

public class StatusActivity extends AppCompatActivity {

    private PostTask postTask = null;
    View button = null;
    protected ProgressDialog progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w("traceinst","cb " + System.identityHashCode(this) + " StatusActivity.onCreate " + System.identityHashCode(savedInstanceState) );
        setContentView(R.layout.main_activity);
        super.onCreate(savedInstanceState);
        Log.w("traceinst","cbret " + System.identityHashCode(this) + " StatusActivity.onCreate " + System.identityHashCode(savedInstanceState) );
    }

    @Override
    protected void onResume(){
        Log.w("traceinst","cb " + System.identityHashCode(this) + " StatusActivity.onResume " );
        super.onResume();
        postTask = new PostTask();
        Log.w("traceinst","new " + " PostTask " + System.identityHashCode(postTask));
        postTask.execute();
        progress = ProgressDialog.show(StatusActivity.this, "Posting",
              	"Please wait...");
        Log.w("traceinst","" + System.identityHashCode(progress) + " = ci " + " ProgressDialog.show "+ System.identityHashCode(StatusActivity.this)  );
        progress.setCancelable(true);

        Log.w("traceinst","ci " + System.identityHashCode(postTask) + " execute");

        Log.w("traceinst","cbret " + System.identityHashCode(this) + " StatusActivity.onResume " );
    }


    class PostTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            Log.w("traceinst","cb " + System.identityHashCode(this) + " PostTask.doInBackground " );
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.w("traceinst","cbret " + System.identityHashCode(this) + " PostTask.doInBackground " );
            return "";
        }

        @Override
        protected void onPostExecute(String result) {
            Log.w("traceinst","cb " + System.identityHashCode(this) + " PostTask.onPostExecute " );
            StatusActivity.this.progress.dismiss();
            Log.w("traceinst","ci " + System.identityHashCode(progress) + " dismiss" );
            Log.w("traceinst","cbret " + System.identityHashCode(this) + " PostTask.onPostExecute " );
        }
    }
}
