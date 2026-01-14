/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.media;

import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import javax.microedition.media.protocol.DataSource;
import javax.microedition.media.protocol.SourceStream;
import javax.microedition.util.ContextHolder;

public class InternalDataSource extends DataSource {
    private static final String TAG = InternalDataSource.class.getName();

    private File mediaFile;
    private final String type;

    public InternalDataSource(InputStream stream, String type) throws IllegalArgumentException, IOException {
        super(null);

        String extension = "." + MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
        this.mediaFile = File.createTempFile("media", extension, ContextHolder.getCacheDir());
        this.type = type;

        final RandomAccessFile raf = new RandomAccessFile(mediaFile, "rw");

        final String name = mediaFile.getName();
        Log.d(TAG, "Starting media pipe: " + name);

        int length = stream.available();
        if (length >= 0) {
            raf.setLength(length);
            Log.d(TAG, "Changing file size to " + length + " bytes: " + name);
        }

        byte[] buf = new byte[0x10000];
        int read;
        try {
            while (true) {
                read = stream.read(buf);
                if (read > 0) {
                    raf.write(buf, 0, read);
                } else if (read < 0) {
                    break;
                }
            }
            raf.close();
            Log.d(TAG, "Media pipe closed: " + name);
        } catch (IOException e) {
            Log.d(TAG, "Media pipe failure: " + e);
        } finally {
            stream.close();
        }

        try {
            convert();
        } catch (Throwable e) {
            // Thrown on fake Oppo devices
            e.printStackTrace();
        }
    }

    private void convert() {
        try {
            // 先尝试使用原生API转换
            File wavFile = new File(mediaFile.getAbsolutePath() + ".wav");
            boolean converted = AudioConverterNative.convertToWavIfNeeded(mediaFile, wavFile);

            if (converted) {
                Log.i(TAG, "Native conversion successful");
                mediaFile.delete();
                mediaFile = wavFile;
            } else {
                Log.i(TAG, "Native conversion not needed or not supported");

                // 可以使用其他方式转换
            }
        } catch (Throwable e) {
            Log.e(TAG, "Audio conversion error", e);
            // 转换失败，保持原文件
        }
    }

    @Override
    public String getLocator() {
        return mediaFile.getAbsolutePath();
    }

    @Override
    public String getContentType() {
        return type;
    }

    @Override
    public void connect() throws IOException {
    }

    @Override
    public void disconnect() {
        if (mediaFile.delete()) {
            Log.d(TAG, "Temp file deleted: " + mediaFile.getAbsolutePath());
        }
    }

    @Override
    public void start() throws IOException {
    }

    @Override
    public void stop() throws IOException {
    }

    @Override
    public SourceStream[] getStreams() {
        return new SourceStream[0];
    }

    @Override
    public Control[] getControls() {
        return new Control[0];
    }

    @Override
    public Control getControl(String control) {
        return null;
    }

}
