package com.example.row2antennapodexecute;

import androidx.appcompat.app.AppCompatActivity;

import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class RemoverActivity extends AppCompatActivity implements View.OnClickListener {

    private FeedRemover remover = null;
    View button = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.w("traceinst","cb " + System.identityHashCode(this) + " RemoverActivity.onCreate " + System.identityHashCode(savedInstanceState) );
        setContentView(R.layout.main_activity);
        super.onCreate(savedInstanceState);
        remover = new FeedRemover();
        Log.w("traceinst","new " + " FeedRemover " + System.identityHashCode(remover));
        button = findViewById(R.id.button);
        Log.w("traceinst",System.identityHashCode(button) + " = ci " + System.identityHashCode(this) + " findViewById " + R.id.button);
        button.setOnClickListener(this);
        Log.w("traceinst","ci " + System.identityHashCode(button) + " setOnClickListener " + System.identityHashCode(this));
        Log.w("traceinst","cbret " + System.identityHashCode(this) + " RemoverActivity.onCreate " + System.identityHashCode(savedInstanceState) );
    }



    @Override
    public void onClick(View view) {
        Log.w("traceinst","cb " + System.identityHashCode(this) + " RemoverActivity.onClick " + System.identityHashCode(view) );
        remover.execute();
        Log.w("traceinst","ci " + System.identityHashCode(remover) + " execute");
        Log.w("traceinst","cbret " + System.identityHashCode(this) + " RemoverActivity.onClick " + System.identityHashCode(view) );
    }

    class FeedRemover extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... params) {
            Log.w("traceinst","cb " + System.identityHashCode(this) + " FeedRemover.doInBackground " );
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            Log.w("traceinst","cbret " + System.identityHashCode(this) + " FeedRemover.doInBackground " );
            return "";
        }

        @Override
        protected void onPostExecute(String result) {
            Log.w("traceinst","cb " + System.identityHashCode(this) + " FeedRemover.onPostExecute " );
            Log.w("traceinst","ci " + System.identityHashCode(RemoverActivity.this) + " finish " );
            RemoverActivity.this.finish();
            Log.w("traceinst","cbret " + System.identityHashCode(this) + " FeedRemover.onPostExecute " );
        }
    }
}