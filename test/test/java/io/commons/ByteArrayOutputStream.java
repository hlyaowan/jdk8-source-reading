/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package test.java.io.commons;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class implements an output stream in which the data is
 * written into a byte array. The buffer automatically grows as data
 * is written to it.
 *
 * 实现了输出流写入到byte数组的过程，byte数组大小是自动扩展的
 *
 * <p>
 * The data can be retrieved using <code>toByteArray()</code> and
 * <code>toString()</code>.
 *
 * 写入完成后可以通过toByteArray()获取数据
 *
 * <p>
 * Closing a {@code ByteArrayOutputStream} has no effect. The methods in
 * this class can be called after the stream has been closed without
 * generating an {@code IOException}.
 *
 * 主动关闭流的方法在该类中是失效的，流将在数据完全写入byte数组后自动关闭
 *
 * <p>
 * This is an alternative implementation of the {@link java.io.ByteArrayOutputStream}
 * class. The original implementation only allocates 32 bytes at the beginning.
 * As this class is designed for heavy duty it starts at 1024 bytes. In contrast
 * to the original it doesn't reallocate the whole memory block but allocates
 * additional buffers. This way no buffers need to be garbage collected and
 * the contents don't have to be copied to the new buffer. This class is
 * designed to behave exactly like the original. The only exception is the
 * deprecated toString(int) method that has been ignored.
 *
 * 该类是java.io.ByteArrayOutputStream的可选后备实现。
 * JDK的实现默认初始化的byte数组大小是32，该类将其改为1024，以便更好的读取较大的数据流
 * 更关键的是该类改变了JDK实现中扩展byte数组的方式，JDK实现扩展时会重新申请一块内存，将原有数据拷贝到新内存前部，然后追加数据
 * 该类则是使用List串联byte数组的方式实现扩展，当currentBuffer用完了，则新创建一个byte数组，将原数组加到List中记录
 * 这种方式只是多了一次从List中寻找已记录的byte数组的过程，却避免了数据拷贝到新内存的过程，降低了拷贝损耗与GC损耗
 * 这个类和JDK的实现几乎是一样的，除了去除了JDK中不推荐使用的toString(int)方法
 */
public class ByteArrayOutputStream extends OutputStream {

    public static final int EOF = -1;

    static final int DEFAULT_SIZE = 1024;

    /** A singleton empty byte array. */
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /** The list of buffers, which grows and never reduces. */
    // 使用一个list来串连已用完的byte数组，避免内存拷贝
    private final List<byte[]> buffers = new ArrayList<>();
    /** The total count of bytes in all the filled buffers. */
    private int filledBufferSum;
    /** The current buffer. */
    private byte[] currentBuffer;
    /** The index of the current buffer. */
    // 当前正在使用的byte数组，由于存在切换，为保证threadSafe使用时必须获取锁
    private int currentBufferIndex;
    /** The total count of bytes written. */
    private int count;
    /** Flag to indicate if the buffers can be reused after reset */
    private boolean reuseBuffers = true;

    /**
     * Creates a new byte array output stream. The buffer capacity is
     * initially 1024 bytes, though its size increases if necessary.
     */
    public ByteArrayOutputStream() {
        this(DEFAULT_SIZE);
    }

    /**
     * Creates a new byte array output stream, with a buffer capacity of
     * the specified size, in bytes.
     *
     * @param size
     *         the initial size
     *
     * @throws IllegalArgumentException
     *         if size is negative
     */
    public ByteArrayOutputStream(final int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Negative initial size: " + size);
        }
        synchronized (this) {
            needNewBuffer(size);
        }
    }

    /**
     * Makes a new buffer available either by allocating
     * a new one or re-cycling an existing one.
     *
     * @param newcount
     *         the size of the buffer if one is created
     */
    private void needNewBuffer(final int newcount) {
        // 查看当前buffer是否已写完
        if (currentBufferIndex < buffers.size() - 1) {
            //Recycling old buffer
            filledBufferSum += currentBuffer.length;

            currentBufferIndex++;
            currentBuffer = buffers.get(currentBufferIndex);
        } else {
            //Creating new buffer
            // 创建一个新的buffer，并把原buffer存储到List里
            int newBufferSize;
            if (currentBuffer == null) {
                newBufferSize = newcount;
                filledBufferSum = 0;
            } else {
                newBufferSize = Math.max(currentBuffer.length << 1, newcount - filledBufferSum);
                filledBufferSum += currentBuffer.length;
            }

            currentBufferIndex++;
            currentBuffer = new byte[newBufferSize];
            buffers.add(currentBuffer);
        }
    }

    /**
     * Write the bytes to byte array.
     *
     * @param b
     *         the bytes to write
     * @param off
     *         The start offset
     * @param len
     *         The number of bytes to write
     */
    @Override
    public void write(final byte[] b, final int off, final int len) {
        if ((off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }
        synchronized (this) {
            final int newcount = count + len;
            int remaining = len;
            int inBufferPos = count - filledBufferSum;
            while (remaining > 0) {
                final int part = Math.min(remaining, currentBuffer.length - inBufferPos);
                System.arraycopy(b, off + len - remaining, currentBuffer, inBufferPos, part);
                remaining -= part;
                if (remaining > 0) {
                    needNewBuffer(newcount);
                    inBufferPos = 0;
                }
            }
            count = newcount;
        }
    }

    /**
     * Write a byte to byte array.
     *
     * @param b
     *         the byte to write
     */
    @Override
    public synchronized void write(final int b) {
        int inBufferPos = count - filledBufferSum;
        if (inBufferPos == currentBuffer.length) {
            needNewBuffer(count + 1);
            inBufferPos = 0;
        }
        currentBuffer[inBufferPos] = (byte) b;
        count++;
    }

    /**
     * Writes the entire contents of the specified input stream to this
     * byte stream. Bytes from the input stream are read directly into the
     * internal buffers of this streams.
     *
     * @param in
     *         the input stream to read from
     *
     * @return total number of bytes read from the input stream
     * (and written to this stream)
     *
     * @throws IOException
     *         if an I/O error occurs while reading the input stream
     * @since 1.4
     */
    public synchronized int write(final InputStream in) throws IOException {
        int readCount = 0;
        int inBufferPos = count - filledBufferSum;
        int n = in.read(currentBuffer, inBufferPos, currentBuffer.length - inBufferPos);
        while (n != EOF) {
            readCount += n;
            inBufferPos += n;
            count += n;
            if (inBufferPos == currentBuffer.length) {
                needNewBuffer(currentBuffer.length);
                inBufferPos = 0;
            }
            n = in.read(currentBuffer, inBufferPos, currentBuffer.length - inBufferPos);
        }
        return readCount;
    }

    /**
     * Return the current size of the byte array.
     *
     * @return the current size of the byte array
     */
    public synchronized int size() {
        return count;
    }

    /**
     * Closing a {@code ByteArrayOutputStream} has no effect. The methods in
     * this class can be called after the stream has been closed without
     * generating an {@code IOException}.
     *
     * @throws IOException
     *         never (this method should not declare this exception
     *         but it has to now due to backwards compatibility)
     */
    @Override
    public void close() throws IOException {
        //nop
    }

    /**
     * @see java.io.ByteArrayOutputStream#reset()
     */
    public synchronized void reset() {
        count = 0;
        filledBufferSum = 0;
        currentBufferIndex = 0;
        if (reuseBuffers) {
            currentBuffer = buffers.get(currentBufferIndex);
        } else {
            //Throw away old buffers
            currentBuffer = null;
            final int size = buffers.get(0).length;
            buffers.clear();
            needNewBuffer(size);
            reuseBuffers = true;
        }
    }

    /**
     * Writes the entire contents of this byte stream to the
     * specified output stream.
     *
     * @param out
     *         the output stream to write to
     *
     * @throws IOException
     *         if an I/O error occurs, such as if the stream is closed
     * @see java.io.ByteArrayOutputStream#writeTo(OutputStream)
     */
    public synchronized void writeTo(final OutputStream out) throws IOException {
        int remaining = count;
        for (final byte[] buf : buffers) {
            final int c = Math.min(buf.length, remaining);
            out.write(buf, 0, c);
            remaining -= c;
            if (remaining == 0) {
                break;
            }
        }
    }

    /**
     * Fetches entire contents of an <code>InputStream</code> and represent
     * same data as result InputStream.
     * <p>
     * This method is useful where,
     * <ul>
     * <li>Source InputStream is slow.</li>
     * <li>It has network resources associated, so we cannot keep it open for
     * long time.</li>
     * <li>It has network timeout associated.</li>
     * </ul>
     * It can be used in favor of {@link #toByteArray()}, since it
     * avoids unnecessary allocation and copy of byte[].<br>
     * This method buffers the input internally, so there is no need to use a
     * <code>BufferedInputStream</code>.
     *
     * @param input
     *         Stream to be fully buffered.
     *
     * @return A fully buffered stream.
     *
     * @throws IOException
     *         if an I/O error occurs
     * @since 2.0
     */
    public static InputStream toBufferedInputStream(final InputStream input) throws IOException {
        return toBufferedInputStream(input, 1024);
    }

    /**
     * Fetches entire contents of an <code>InputStream</code> and represent
     * same data as result InputStream.
     * <p>
     * This method is useful where,
     * <ul>
     * <li>Source InputStream is slow.</li>
     * <li>It has network resources associated, so we cannot keep it open for
     * long time.</li>
     * <li>It has network timeout associated.</li>
     * </ul>
     * It can be used in favor of {@link #toByteArray()}, since it
     * avoids unnecessary allocation and copy of byte[].<br>
     * This method buffers the input internally, so there is no need to use a
     * <code>BufferedInputStream</code>.
     *
     * @param input
     *         Stream to be fully buffered.
     * @param size
     *         the initial buffer size
     *
     * @return A fully buffered stream.
     *
     * @throws IOException
     *         if an I/O error occurs
     * @since 2.5
     */
    public static InputStream toBufferedInputStream(final InputStream input, final int size) throws IOException {
        // It does not matter if a ByteArrayOutputStream is not closed as close() is a no-op
        @SuppressWarnings("resource")
        final ByteArrayOutputStream output = new ByteArrayOutputStream(size);
        output.write(input);
        return output.toInputStream();
    }

    /**
     * Gets the current contents of this byte stream as a Input Stream. The
     * returned stream is backed by buffers of <code>this</code> stream,
     * avoiding memory allocation and copy, thus saving space and time.<br>
     *
     * @return the current contents of this output stream.
     *
     * @see java.io.ByteArrayOutputStream#toByteArray()
     * @see #reset()
     * @since 2.5
     */
    public synchronized InputStream toInputStream() {
        int remaining = count;
        if (remaining == 0) {
            return new ClosedInputStream();
        }
        final List<ByteArrayInputStream> list = new ArrayList<>(buffers.size());
        for (final byte[] buf : buffers) {
            final int c = Math.min(buf.length, remaining);
            list.add(new ByteArrayInputStream(buf, 0, c));
            remaining -= c;
            if (remaining == 0) {
                break;
            }
        }
        reuseBuffers = false;
        return new SequenceInputStream(Collections.enumeration(list));
    }

    /**
     * Gets the current contents of this byte stream as a byte array.
     * The result is independent of this stream.
     *
     * @return the current contents of this output stream, as a byte array
     *
     * @see java.io.ByteArrayOutputStream#toByteArray()
     */
    public synchronized byte[] toByteArray() {
        int remaining = count;
        if (remaining == 0) {
            return EMPTY_BYTE_ARRAY;
        }
        final byte newbuf[] = new byte[remaining];
        int pos = 0;
        for (final byte[] buf : buffers) {
            final int c = Math.min(buf.length, remaining);
            System.arraycopy(buf, 0, newbuf, pos, c);
            pos += c;
            remaining -= c;
            if (remaining == 0) {
                break;
            }
        }
        return newbuf;
    }

    /**
     * Gets the current contents of this byte stream as a string
     * using the platform default charset.
     *
     * @return the contents of the byte array as a String
     *
     * @see java.io.ByteArrayOutputStream#toString()
     * @deprecated 2.5 use {@link #toString(String)} instead
     */
    @Override
    @Deprecated
    public String toString() {
        // make explicit the use of the default charset
        return new String(toByteArray(), Charset.defaultCharset());
    }

    /**
     * Gets the current contents of this byte stream as a string
     * using the specified encoding.
     *
     * @param enc
     *         the name of the character encoding
     *
     * @return the string converted from the byte array
     *
     * @throws UnsupportedEncodingException
     *         if the encoding is not supported
     * @see java.io.ByteArrayOutputStream#toString(String)
     */
    public String toString(final String enc) throws UnsupportedEncodingException {
        return new String(toByteArray(), enc);
    }

    /**
     * Gets the current contents of this byte stream as a string
     * using the specified encoding.
     *
     * @param charset
     *         the character encoding
     *
     * @return the string converted from the byte array
     *
     * @see java.io.ByteArrayOutputStream#toString(String)
     * @since 2.5
     */
    public String toString(final Charset charset) {
        return new String(toByteArray(), charset);
    }

}
