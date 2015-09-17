package com.android.myexoplayer;

import android.support.v7.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaCodecUtil.DecoderQueryException;
import com.android.myexoplayer.Samples.Sample;
import com.google.android.exoplayer.util.MimeTypes;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ListView sampleList = (ListView) findViewById(R.id.sample_list);
        final SampleAdapter sampleAdapter = new SampleAdapter(this);

        sampleAdapter.add(new Header("YouTube DASH"));
        sampleAdapter.addAll((Object[]) Samples.YOUTUBE_DASH_MP4);
        sampleAdapter.add(new Header("Widevine GTS DASH"));
        sampleAdapter.addAll((Object[]) Samples.WIDEVINE_GTS);
        sampleAdapter.add(new Header("SmoothStreaming"));
        sampleAdapter.addAll((Object[]) Samples.SMOOTHSTREAMING);
        sampleAdapter.add(new Header("HLS"));
        sampleAdapter.addAll((Object[]) Samples.HLS);
        sampleAdapter.add(new Header("Misc"));
        sampleAdapter.addAll((Object[]) Samples.MISC);

        // Add WebM samples if the device has a VP9 decoder.
        try {
            if (MediaCodecUtil.getDecoderInfo(MimeTypes.VIDEO_VP9, false) != null) {
                sampleAdapter.add(new Header("YouTube WebM DASH (Experimental)"));
                sampleAdapter.addAll((Object[]) Samples.YOUTUBE_DASH_WEBM);
            }
        } catch (DecoderQueryException e) {
            Log.e(TAG, "Failed to query vp9 decoder", e);
        }

        sampleList.setAdapter(sampleAdapter);
        sampleList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object item = sampleAdapter.getItem(position);
                if (item instanceof Sample) {
                    onSampleSelected((Sample) item);
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void onSampleSelected(Sample sample) {
        Intent mpdIntent = new Intent(this, PlayerActivity.class)
                .setData(Uri.parse(sample.uri))
                .putExtra(PlayerActivity.CONTENT_ID_EXTRA, sample.contentId)
                .putExtra(PlayerActivity.CONTENT_TYPE_EXTRA, sample.type);
        startActivity(mpdIntent);
    }


    private static class SampleAdapter extends ArrayAdapter<Object> {

        public SampleAdapter(Context context) {
            super(context, 0);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                int layoutId = getItemViewType(position) == 1 ? android.R.layout.simple_list_item_1
                        : R.layout.sample_chooser_inline_header;
                view = LayoutInflater.from(getContext()).inflate(layoutId, null, false);
            }
            Object item = getItem(position);
            String name = null;
            if (item instanceof Sample) {
                name = ((Sample) item).name;
            } else if (item instanceof Header) {
                name = ((Header) item).name;
            }
            ((TextView) view).setText(name);
            return view;
        }

        @Override
        public int getItemViewType(int position) {
            return (getItem(position) instanceof Sample) ? 1 : 0;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

    }

    private static class Header {

        public final String name;

        public Header(String name) {
            this.name = name;
        }

    }
}
