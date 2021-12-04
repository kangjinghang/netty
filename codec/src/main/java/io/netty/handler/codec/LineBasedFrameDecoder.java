/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * <p>
 * The byte stream is expected to be in UTF-8 character encoding or ASCII. The current implementation
 * uses direct {@code byte} to {@code char} cast and then compares that {@code char} to a few low range
 * ASCII characters like {@code '\n'} or {@code '\r'}. UTF-8 is not using low range [0..0x7F]
 * byte values for multibyte codepoint representations therefore fully supported by this implementation.
 * <p>
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    private final boolean failFast;
    private final boolean stripDelimiter; // 取包的时候是否包括分隔符

    /** True if we're discarding input because we're already over maxLength.  */
    private boolean discarding; // 丢弃模式
    private int discardedBytes;

    /** Last scan position. */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        final int eol = findEndOfLine(buffer);
        if (!discarding) {
            if (eol >= 0) { // 1.新建一个帧，计算一下当前包的长度和分隔符的长度（因为有两种分隔符）
                final ByteBuf frame;
                final int length = eol - buffer.readerIndex(); // 当前包的长度
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; // 分隔符是 \r\n 的话，delimLength=2，否则=1
                // 丢弃异常数据
                if (length > maxLength) { // 然后判断一下需要拆包的长度是否大于该拆包器允许的最大长度(maxLength)，这个参数在构造函数中被传递进来，如超出允许的最大长度，就将这段数据抛弃，返回null
                    buffer.readerIndex(eol + delimLength); // 将这段数据抛弃
                    fail(ctx, length);
                    return null;
                }
                // 将一个完整的数据包取出，将一个完整的数据包取出，如果构造当前解包器的时候指定 stripDelimiter为false，即解析出来的包包含分隔符，默认为不包含分隔符
                if (stripDelimiter) { // 解析出来的包不包含分隔符
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    frame = buffer.readRetainedSlice(length + delimLength); // 解析出来的包包含分隔符
                }

                return frame;
            } else { // 没有找到对应的行分隔符，说明字节容器没有足够的数据拼接成一个完整的业务数据包
                final int length = buffer.readableBytes(); // 取得当前字节容器的可读字节个数
                if (length > maxLength) { // 判断一下是否已经超过可允许的最大长度，如果已经超过
                    discardedBytes = length; // 进入丢弃模式，discardedBytes 来表示已经丢弃了多少数据
                    buffer.readerIndex(buffer.writerIndex()); // 将字节容器的读指针移到写指针，意味着丢弃这一部分数据
                    discarding = true; // 表示当前处于丢弃模式
                    offset = 0;
                    if (failFast) { // 如果设置了failFast，那么直接抛出异常。默认情况下failFast为false，即安静得丢弃数据
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null; // 没有超过可允许的最大长度，直接返回null，字节容器中的数据没有任何改变
            }
        } else { // 在discarding丢弃模式下
            if (eol >= 0) { // 读取了多次，字节容器有了足够的数据，拼接成了一个完整的业务数据包，找到了分隔符
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1; // 计算出分隔符的长度
                buffer.readerIndex(eol + delimLength); // 把分隔符之前的数据全部丢弃
                discardedBytes = 0; // 之前的包（因为超过了maxLength，不要了，可以直接丢掉）都丢掉了
                discarding = false; // 变为非丢弃模式，后面就有可能是正常的数据包，下一次解包的时候就会进入正常的解包流程
                if (!failFast) {
                    fail(ctx, length);
                }
            } else { // 意味着当前一个完整的数据包还没丢弃完，当前读取的数据是要被丢弃的一部分，所以直接丢弃。
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null; // 这样下次在父类里还会继续循环读
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    private int findEndOfLine(final ByteBuf buffer) { // for循环遍历，找到第一个\n的位置,如果\n前面的字符为\r，那就返回\r的位置
        int totalLength = buffer.readableBytes();
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            offset = 0;
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            offset = totalLength;
        }
        return i;
    }
}
