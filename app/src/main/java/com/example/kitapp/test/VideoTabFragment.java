
package com.example.kitapp.test;

import static android.content.ContentValues.TAG;

import static com.example.kitapp.test.MainActivity.notNull;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.MediaController;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Statistics;
import com.arthenica.ffmpegkit.StatisticsCallback;
import com.example.kitapp.R;
import com.example.kitapp.util.DialogUtil;

import com.arthenica.smartexception.java.Exceptions;

import java.io.File;
import java.math.BigDecimal;

public class VideoTabFragment extends Fragment implements AdapterView.OnItemSelectedListener {
    private VideoView videoView;
    private AlertDialog progressDialog;
    private String selectedCodec;
    private Statistics statistics;
    private static final int PICK_VIDEO_REQUEST = 1;

    public VideoTabFragment() {
        super(R.layout.fragment_video_tab);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Spinner videoCodecSpinner = view.findViewById(R.id.videoCodecSpinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(requireContext(),
                R.array.video_codec, R.layout.spinner_item);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        videoCodecSpinner.setAdapter(adapter);
        videoCodecSpinner.setOnItemSelectedListener(this);

        View encodeButton = view.findViewById(R.id.encodeButton);
        if (encodeButton != null) {
            encodeButton.setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    pickVideo();
                }
            });
        }

        videoView = view.findViewById(R.id.videoPlayerFrame);

        progressDialog = DialogUtil.createProgressDialog(requireContext(), "Encoding video");

        selectedCodec = getResources().getStringArray(R.array.video_codec)[0];
    }

    @Override
    public void onResume() {
        super.onResume();
        setActive();
    }

    public static VideoTabFragment newInstance() {
        return new VideoTabFragment();
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        selectedCodec = parent.getItemAtPosition(position).toString();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        // DO NOTHING
    }

    public void encodeVideo(String inputVideoPath) {
        // Get the input video file path from the user's local storage
        //String inputVideoPath = "path_to_user_input_video"; // Replace with the actual path

        // Generate the output video file path based on the selected codec
        String outputVideoPath = getCompressedVideoFilePath();

        try {
            // Stop video playback if it's currently playing
            videoView.stopPlayback();

            // Delete the existing output video file if it exists
            File outputVideoFile = new File(outputVideoPath);
            if (outputVideoFile.exists()) {
                outputVideoFile.delete();
            }

            // Get the selected video codec and other options
            final String videoCodec = getSelectedVideoCodec();
            final String pixelFormat = getPixelFormat();
            final String customOptions = getCustomOptions();

            // Show progress dialog
            showProgressDialog();

            // Generate the FFmpeg command to compress the video
            final String ffmpegCommand = String.format("ffmpeg -i %s -c:v %s -crf 23 -preset medium -c:a aac -b:a 192k %s",
                    inputVideoPath, videoCodec, outputVideoPath);

            // Execute FFmpeg asynchronously
            final FFmpegSession session = FFmpegKit.executeAsync(ffmpegCommand, new FFmpegSessionCompleteCallback() {
                @Override
                public void apply(final FFmpegSession session) {
                    final ReturnCode returnCode = session.getReturnCode();

                    // Hide progress dialog
                    hideProgressDialog();

                    // Perform UI actions based on the compression result
                    MainActivity.addUIAction(new Runnable() {
                        @Override
                        public void run() {
                            if (ReturnCode.isSuccess(returnCode)) {
                                // Compression completed successfully, play the compressed video
                                Log.d(TAG, String.format("Compression completed successfully in %d milliseconds; playing video.", session.getDuration()));
                                playVideo(outputVideoPath);
                            } else {
                                // Compression failed, show an error message
                                Popup.show(requireContext(), "Compression failed. Please check logs for details.");
                                Log.d(TAG, String.format("Compression failed with state %s and rc %s.%s", session.getState(), returnCode, notNull(session.getFailStackTrace(), "\n")));
                            }
                        }
                    });
                }
            }, new LogCallback() {
                @Override
                public void apply(com.arthenica.ffmpegkit.Log log) {
                    Log.d(MainActivity.TAG, log.getMessage());
                }
            }, new StatisticsCallback() {
                @Override
                public void apply(Statistics statistics) {
                    VideoTabFragment.this.statistics = statistics;
                    // Update progress dialog during compression
                    MainActivity.addUIAction(new Runnable() {
                        @Override
                        public void run() {
                            updateProgressDialog();
                        }
                    });
                }
            });

            Log.d(TAG, String.format("Async FFmpeg process started with sessionId %d.", session.getSessionId()));

        } catch (Exception e) {
            // Handle exceptions
            Log.e(TAG, String.format("Video compression failed: %s.", Exceptions.getStackTraceString(e)));
            Popup.show(requireContext(), "Video compression failed");
        }
    }

    // Method to play the compressed video
    private void playVideo(String videoPath) {
        MediaController mediaController = new MediaController(requireContext());
        mediaController.setAnchorView(videoView);
        //videoView.setVideoURI(Uri.parse("file://" + videoPath));
        videoView.setMediaController(mediaController);
        videoView.setVideoURI(Uri.parse("file://" + videoPath));

        videoView.requestFocus();
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                videoView.setBackgroundColor(0x00000000);
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                videoView.stopPlayback();
                return false;
            }
        });
        videoView.start();
    }

    // Method to generate the output video file path based on the selected codec
    private String getCompressedVideoFilePath() {
        String videoCodec = selectedCodec;

        // Determine the appropriate file extension based on the selected codec
        final String extension;
        switch (videoCodec) {
            case "vp8":
            case "vp9":
                extension = "webm";
                break;
            case "aom":
                extension = "mkv";
                break;
            case "theora":
                extension = "ogv";
                break;
            case "hap":
                extension = "mov";
                break;
            default:
                // mpeg4, x264, h264_mediacodec, hevc_mediacodec, x265, xvid, kvazaar
                extension = "mp4";
                break;
        }

        // Construct the output video file path
        final String video = "compressed_video." + extension;
        return new File(requireContext().getFilesDir(), video).getAbsolutePath();
    }
    public String getPixelFormat() {
        String videoCodec = selectedCodec;

        final String pixelFormat;
        if ("x265".equals(videoCodec)) {
            pixelFormat = "yuv420p10le";
        } else {
            pixelFormat = "yuv420p";
        }

        return pixelFormat;
    }

    public String getSelectedVideoCodec() {
        String videoCodec = selectedCodec;

        // VIDEO CODEC SPINNER HAS BASIC NAMES, FFMPEG NEEDS LONGER AND EXACT CODEC NAMES.
        // APPLYING NECESSARY TRANSFORMATION HERE
        switch (videoCodec) {
            case "x264":
                videoCodec = "libx264";
                break;
            case "h264_mediacodec":
                videoCodec = "h264_mediacodec";
                break;
            case "hevc_mediacodec":
                videoCodec = "hevc_mediacodec";
                break;
            case "openh264":
                videoCodec = "libopenh264";
                break;
            case "x265":
                videoCodec = "libx265";
                break;
            case "xvid":
                videoCodec = "libxvid";
                break;
            case "vp8":
                videoCodec = "libvpx";
                break;
            case "vp9":
                videoCodec = "libvpx-vp9";
                break;
            case "aom":
                videoCodec = "libaom-av1";
                break;
            case "kvazaar":
                videoCodec = "libkvazaar";
                break;
            case "theora":
                videoCodec = "libtheora";
                break;
        }

        return videoCodec;
    }

    public File getVideoFile() {
        String videoCodec = selectedCodec;

        final String extension;
        switch (videoCodec) {
            case "vp8":
            case "vp9":
                extension = "webm";
                break;
            case "aom":
                extension = "mkv";
                break;
            case "theora":
                extension = "ogv";
                break;
            case "hap":
                extension = "mov";
                break;
            default:

                // mpeg4, x264, h264_mediacodec, hevc_mediacodec, x265, xvid, kvazaar
                extension = "mp4";
                break;
        }

        final String video = "video." + extension;
        return new File(requireContext().getFilesDir(), video);
    }

    public String getCustomOptions() {
        String videoCodec = selectedCodec;

        switch (videoCodec) {
            case "x265":
                return "-crf 28 -preset fast ";
            case "vp8":
                return "-b:v 1M -crf 10 ";
            case "vp9":
                return "-b:v 2M ";
            case "aom":
                return "-crf 30 -strict experimental ";
            case "theora":
                return "-qscale:v 7 ";
            case "hap":
                return "-format hap_q ";
            default:

                // kvazaar, mpeg4, x264, h264_mediacodec, hevc_mediacodec, xvid
                return "";
        }
    }

    public void setActive() {
        Log.i(MainActivity.TAG, "Video Tab Activated");
        FFmpegKitConfig.enableLogCallback(null);
        FFmpegKitConfig.enableStatisticsCallback(null);
        Popup.show(requireContext(), getString(R.string.video_test_tooltip_text));
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedVideoUri = data.getData();
            // Perform operations with the selected video URI
            // e.g., display the video path or start video compression
            String selectedVideoPath = getRealPathFromURI(selectedVideoUri);
            if (selectedVideoPath != null) {
                // Continue with compression and playback
                encodeVideo(selectedVideoPath);
            }
        }
    }

    private String getRealPathFromURI(Uri uri) {
        String[] projection = {MediaStore.Video.Media.DATA};
        Cursor cursor = getActivity().getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return null;
    }

    protected void showProgressDialog() {

        // CLEAN STATISTICS
        statistics = null;

        progressDialog.show();
    }

    protected void updateProgressDialog() {
        if (statistics == null || statistics.getTime() < 0) {
            return;
        }

        double timeInMilliseconds = this.statistics.getTime();
        int totalVideoDuration = 9000;

        String completePercentage = new BigDecimal(timeInMilliseconds).multiply(new BigDecimal(100)).divide(new BigDecimal(totalVideoDuration), 0, BigDecimal.ROUND_HALF_UP).toString();

        TextView textView = progressDialog.findViewById(R.id.progressDialogText);
        if (textView != null) {
            textView.setText(String.format("Encoding video: %% %s.", completePercentage));
        }
    }

    protected void hideProgressDialog() {
        progressDialog.dismiss();

        MainActivity.addUIAction(new Runnable() {

            @Override
            public void run() {
                VideoTabFragment.this.progressDialog = DialogUtil.createProgressDialog(requireContext(), "Encoding video");
            }
        });
    }
    private void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        startActivityForResult(intent, PICK_VIDEO_REQUEST);
    }

}
