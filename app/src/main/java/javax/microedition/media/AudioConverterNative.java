package javax.microedition.media;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class AudioConverterNative {
    private static final String TAG = "AudioConverterNative";

    // ADPCM相关的MIME类型
    private static final String[] ADPCM_MIME_TYPES = {
            "audio/adpcm",
            "audio/x-adpcm",
            "audio/adpcm-ima",
            "audio/adpcm-ms",
            "audio/adpcm-yamaha",
            "audio/g722"
    };

    public static boolean convertToWavIfNeeded(File inputFile, File outputFile) {

        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            // 查找音频轨道
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = format;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                Log.w(TAG, "No audio track found");
                return false;
            }

            // 检查是否需要转换（ADPCM格式）
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null || !isAdpcmMimeType(mime.toLowerCase())) {
                Log.d(TAG, "Not ADPCM format (" + mime + "), no conversion needed");
                return false;
            }

            Log.i(TAG, "Converting ADPCM to WAV: " + mime);

            // 获取音频参数
            int sampleRate = 16000; // 默认值
            int channelCount = 1;   // 默认值

            if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }

            if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }

            // 记录参数
            Log.d(TAG, "Audio parameters - SampleRate: " + sampleRate +
                    ", Channels: " + channelCount +
                    ", MIME: " + mime);

            extractor.selectTrack(audioTrackIndex);

            // 创建解码器
            try {
                decoder = MediaCodec.createDecoderByType(mime);
            } catch (IOException e) {
                Log.w(TAG, "Codec not supported: " + mime, e);
                return false;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid codec type: " + mime, e);
                return false;
            }

            // 配置解码器
            try {
                decoder.configure(inputFormat, null, null, 0);
            } catch (Exception e) {
                Log.w(TAG, "Failed to configure decoder for: " + mime, e);
                return false;
            }

            decoder.start();

            // 解码并写入WAV
            boolean success = decodeToWav(extractor, decoder, sampleRate, channelCount, outputFile);

            if (success) {
                Log.i(TAG, "ADPCM to WAV conversion successful: " +
                        inputFile.getName() + " -> " + outputFile.getName());
            } else {
                Log.w(TAG, "ADPCM to WAV conversion failed");
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Native conversion failed", e);
            // 删除可能部分写入的输出文件
            if (outputFile.exists()) {
                boolean deleted = outputFile.delete();
                Log.d(TAG, "Deleted partially written output file: " + deleted);
            }
            return false;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing extractor", e);
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping decoder", e);
                }
                try {
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing decoder", e);
                }
            }
        }
    }

    private static boolean isAdpcmMimeType(String mime) {
        if (mime == null) {
            return false;
        }

        // 检查是否是ADPCM类型
        for (String adpcmType : ADPCM_MIME_TYPES) {
            if (mime.contains(adpcmType)) {
                return true;
            }
        }

        // 检查其他可能表示ADPCM的标识
        return mime.contains("adpcm") ||
                mime.contains("ima") ||
                mime.contains("g722");
    }

    private static boolean decodeToWav(MediaExtractor extractor, MediaCodec decoder,
                                       int sampleRate, int channelCount,
                                       File outputFile) throws IOException {
        final long kTimeoutUs = 10000;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteArrayOutputStream pcmData = new ByteArrayOutputStream();

        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int totalDecodedBytes = 0;
        int inputFrameCount = 0;
        int outputFrameCount = 0;

        Log.d(TAG, "Starting decode process");

        while (!sawOutputEOS) {
            // 输入数据
            if (!sawInputEOS) {
                int inputBufferIndex = decoder.dequeueInputBuffer(kTimeoutUs);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);

                    if (inputBuffer == null) {
                        Log.w(TAG, "Input buffer is null");
                        break;
                    }

                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        // 输入结束
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0,
                                0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                        Log.d(TAG, "Input EOS reached");
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferIndex, 0,
                                sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                        inputFrameCount++;

                        if (inputFrameCount % 100 == 0) {
                            Log.v(TAG, "Processed " + inputFrameCount + " input frames");
                        }
                    }
                }
            }

            // 输出数据
            int outputBufferIndex = decoder.dequeueOutputBuffer(info, kTimeoutUs);

            if (outputBufferIndex >= 0) {
                ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);

                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);

                    byte[] chunk = new byte[info.size];
                    outputBuffer.get(chunk);
                    pcmData.write(chunk);

                    totalDecodedBytes += info.size;
                    outputFrameCount++;

                    if (outputFrameCount % 100 == 0) {
                        Log.v(TAG, "Decoded " + outputFrameCount + " frames, total bytes: " + totalDecodedBytes);
                    }
                }

                decoder.releaseOutputBuffer(outputBufferIndex, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                    Log.d(TAG, "Output EOS reached, total decoded: " + totalDecodedBytes + " bytes");
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Log.d(TAG, "Output buffers changed");
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = decoder.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);

                // 更新采样率和通道数（如果有变化）
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }

        }

        // 检查是否有解码数据
        byte[] pcmBytes = pcmData.toByteArray();
        if (pcmBytes.length == 0) {
            Log.w(TAG, "No PCM data decoded");
            return false;
        }

        Log.i(TAG, "Successfully decoded " + pcmBytes.length +
                " bytes of PCM data from " + inputFrameCount + " input frames");

        // 写入WAV文件
        try {
            writeWavFile(outputFile, pcmBytes, sampleRate, channelCount, 16);
            Log.i(TAG, "WAV file created: " + outputFile.getAbsolutePath() +
                    " (" + outputFile.length() + " bytes)");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to write WAV file", e);
            return false;
        }
    }

    private static void writeWavFile(File file, byte[] pcmData,
                                     int sampleRate, int channels, int bitsPerSample)
            throws IOException {
        if (pcmData == null || pcmData.length == 0) {
            throw new IOException("No PCM data to write");
        }

        try (FileOutputStream fos = new FileOutputStream(file)) {
            // 计算WAV文件参数
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;
            int dataSize = pcmData.length;
            int totalSize = 36 + dataSize;

            Log.d(TAG, "Writing WAV: SampleRate=" + sampleRate +
                    ", Channels=" + channels +
                    ", BitsPerSample=" + bitsPerSample +
                    ", DataSize=" + dataSize);

            // RIFF头
            fos.write("RIFF".getBytes(StandardCharsets.US_ASCII)); // ChunkID
            writeLittleEndianInt(fos, totalSize);    // ChunkSize (文件大小-8)
            fos.write("WAVE".getBytes(StandardCharsets.US_ASCII)); // Format

            // fmt子块
            fos.write("fmt ".getBytes(StandardCharsets.US_ASCII)); // Subchunk1ID
            writeLittleEndianInt(fos, 16);           // Subchunk1Size (16 for PCM)
            writeLittleEndianShort(fos, 1);          // AudioFormat (1 = PCM)
            writeLittleEndianShort(fos, channels);   // NumChannels
            writeLittleEndianInt(fos, sampleRate);   // SampleRate
            writeLittleEndianInt(fos, byteRate);     // ByteRate
            writeLittleEndianShort(fos, blockAlign); // BlockAlign
            writeLittleEndianShort(fos, bitsPerSample); // BitsPerSample

            // data子块
            fos.write("data".getBytes(StandardCharsets.US_ASCII)); // Subchunk2ID
            writeLittleEndianInt(fos, dataSize);     // Subchunk2Size
            fos.write(pcmData);                       // PCM数据

            fos.flush();
        }
    }

    private static void writeLittleEndianInt(FileOutputStream fos, int value) throws IOException {
        fos.write(value & 0xFF);
        fos.write((value >> 8) & 0xFF);
        fos.write((value >> 16) & 0xFF);
        fos.write((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(FileOutputStream fos, int value) throws IOException {
        fos.write(value & 0xFF);
        fos.write((value >> 8) & 0xFF);
    }
}