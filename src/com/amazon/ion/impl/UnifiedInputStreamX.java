package com.amazon.ion.impl;

import com.amazon.ion.impl.IonReaderTextRawTokensX.IonReaderTextTokenException;
import com.amazon.ion.impl.UnifiedSavePointManagerX.SavePoint;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;


public abstract class UnifiedInputStreamX
{
    public  static final int     EOF = -1;

    private static final boolean _debug = false;
            static final int     UNREAD_LIMIT = 10;

    static int DEFAULT_PAGE_SIZE;
    static {
        if (_debug) {
            DEFAULT_PAGE_SIZE = 32;
        }
        else {
            DEFAULT_PAGE_SIZE = 32*1024;
        }
    }

    //
    // member variables
    //
    boolean                 _eof;
    boolean                 _is_byte_data;
    boolean                 _is_stream;

    UnifiedInputBufferX      _buffer;
    int                     _max_char_value;
    int                     _pos;
    int                     _limit;

    // only 1 of these will be filled in depending on whether this is a byte
    // source or a character source
    Reader                  _reader;
    InputStream             _stream;
    byte[]                  _bytes;
    char[]                  _chars;

    UnifiedSavePointManagerX _save_points;


    // factories to construct an appropriate input stream
    // based on the input source
    public static UnifiedInputStreamX makeStream(CharSequence chars) {
        return new FromCharArray(chars, 0, chars.length());
    }
    public static UnifiedInputStreamX makeStream(CharSequence chars, int offset, int length) {
        return new FromCharArray(chars, offset, length);
    }
    public static UnifiedInputStreamX makeStream(char[] chars) {
        return new FromCharArray(chars, 0, chars.length);
    }
    public static UnifiedInputStreamX makeStream(char[] chars, int offset, int length) {
        return new FromCharArray(chars, offset, length);
    }
    public static UnifiedInputStreamX makeStream(Reader reader) throws IOException {
        return new FromCharStream(reader);
    }
    public static UnifiedInputStreamX makeStream(byte[] buffer) {
        return new FromByteArray(buffer, 0, buffer.length);
    }
    public static UnifiedInputStreamX makeStream(byte[] buffer, int offset, int length) {
        return new FromByteArray(buffer, offset, length);
    }
    public static UnifiedInputStreamX makeStream(InputStream stream) throws IOException {
        return new FromByteStream(stream);
    }
    public final InputStream getInputStream() { return _stream; }
    public final Reader      getReader()      { return _reader; }
    public final byte[]      getByteArray()   { return _bytes; }
    public final char[]      getCharArray()   { return _chars; }

    private final void init() {
        // _state = UIS_STATE.STATE_READING;
        _eof = false;
        _max_char_value = _buffer.maxValue();
        _save_points = new UnifiedSavePointManagerX(this);
    }

    public final boolean isEOF() {
        return _eof; // (_state == UIS_STATE.STATE_EOF);
    }

    /**
     * used to find the current position of this stream in the
     * input source.
     * @return current "file" position
     */
    public long getPosition() {
        long file_pos = _buffer.getCurrentPage().getFilePosition(_pos);
        return file_pos;
    }

    /*
     * save point handling - most of the heavy lifting is handled
     * by the PageBuffer.  The local offset in the current page
     * is added by these routines (from the local member _pos)
     * when needed by the save point handling.
     *
     * these covers also handle keeping _save_point_active and
     * _save_point_limit up to date as they handle their normal
     * work.
     *
     * savepoints were not intended to overlap (save in a save
     * point) so while there isn't any obvious or intentional
     * reason this won't work, it's not planned for nor tested
     * and, therefore, likely to have problems.  There is no
     * need for overlapping save point for the parsing case.
     */
    public final SavePoint savePointAllocate() {
        SavePoint sp = _save_points.savePointAllocate();
        return sp;
    }

    protected final void save_point_reset_to_prev(SavePoint sp)
    {
        int             idx = sp.getPrevIdx();
        UnifiedDataPageX curr = _buffer.getPage(idx);
        int             pos = sp.getPrevPos();
        int             limit = sp.getPrevLimit();

        make_page_current(curr, idx, pos, limit);
    }

    protected final void make_page_current(UnifiedDataPageX curr, int idx, int pos, int limit)
    {
        if (pos > limit) {
            refill_is_eof();
            return;
        }
        _eof = false;
        _limit = limit;
        _pos = pos;
        if (is_byte_data()) {
            _bytes = curr.getByteBuffer();
        }
        else {
            _chars = curr.getCharBuffer();
        }
        _buffer.setCurrentPage(idx, curr);
    }

    private final boolean is_byte_data() {
        return _is_byte_data;
    }
    public final void unread(int c)
    {
        if (c == -1) {
            return;
        }
        else if (c < 0 || c > _max_char_value) {
            throw new IllegalArgumentException();
        }
        if (_eof) {
            _eof = false;
            if (_limit == -1) {
                _limit = _pos;
            }
        }
        _pos--;
        if (_pos >= 0) {
            if (is_byte_data()) {
                _bytes[_pos] = (byte)c;
            }
            else {
                _chars[_pos] = (char)c;
            }
            UnifiedDataPageX curr = _buffer.getCurrentPage();
            if (_pos < curr.getStartingOffset()) {
                curr.inc_unread_count();
            }
        }
        else {
            _buffer.putCharAt(getPosition(), c);
        }
    }

    public final int read() throws IOException {
        if (_pos >= _limit) return read_helper();
        // both bytes and chars might be null if this is empty input
        // otherwise we should have 1, and only 1, of these buffers set
        assert((_bytes == null) ^ (_chars == null));
        return (_is_byte_data) ? (_bytes[_pos++] & 0xff) : _chars[_pos++];
    }

    protected final int read_helper() throws IOException
    {
        if (_eof) {
            return EOF;
        }
        if (refill_helper()) {
            return EOF;
        }

        int c = (is_byte_data()) ? (_bytes[_pos++] & 0xff) : _chars[_pos++];
        return c;
    }

    private final boolean refill_helper() throws IOException
    {
        _limit = refill();
        // done in refill: _pos = _buffer.getCurrentPage().getOriginalStartingOffset();
        if (_pos >= _limit) {
            _eof = true;
            return true;
        }
        return false;
    }

    public final void skip(int skipDistance) throws IOException
    {
        int remaining;

        if (_pos <= _limit - skipDistance) {
            _pos += skipDistance;
            remaining = 0;
        }
        else {
            remaining = skipDistance;
            while (remaining > 0) {
                int ready = _limit - _pos;
                if (ready > remaining) {
                    ready = remaining;
                }
                remaining -= ready;
                if (remaining > 0) {
                    if (refill_helper()) {
                        break;
                    }
                }
            }
        }
        if (remaining > 0) {
            String message = "unexpected EOF encountered during skip of "
                           + skipDistance
                           + " at position "
                           + getPosition();
            throw new IOException(message);
        }
        return;
    }
    public final int read(byte[] dst, int offset, int length) throws IOException
    {
        if (!is_byte_data()) {
            throw new IOException("byte read is not support over character sources");
        }
        int remaining = length;
        while (remaining > 0 && !isEOF()) {
            int ready = _limit - _pos;
            if (ready > remaining) {
                ready = remaining;
            }
            System.arraycopy(_bytes, _pos, dst, offset, ready);
            _pos += ready;
            offset += ready;
            remaining -= ready;
            if (_pos < _limit || refill_helper()) {
                break;
            }
        }
        return length - remaining;
    }
    public final int read(char[] dst, int offset, int length) throws IOException
    {
        if (is_byte_data()) {
            throw new IOException("character read is not support over byte data sources");
        }
        int remaining = length;
        while (remaining > 0 && !isEOF()) {
            int ready = _limit - _pos;
            if (ready > remaining) {
                ready = remaining;
            }
            System.arraycopy(_chars, _pos, dst, offset, ready);
            _pos += ready;
            offset += ready;
            if (refill_helper()) {
                break;
            }
        }
        return length - remaining;
    }

    public int readScalar() throws IOException
    {
        int c;

        c = read();
        if (c != EOF && _is_byte_data && IonUTF8.isStartByte(c)) {
            c = read_utf8(c);
        }
        return c;
    }
    private int read_utf8(int c) throws IOException
    {
        int len = IonUTF8.getUTF8LengthFromFirstByte(c);
        for (int ii=1; ii<len; ii++) {
            int c2 = read();
            if (c2 == -1) {
                throw new IonReaderTextTokenException("invalid UTF8 sequence encountered in stream");
            }
            c |= (c2 << (ii*8));
        }
        c = IonUTF8.getScalarFrom4BytesReversed(c);
        return c;
    }

    /**
     * the refill method is the key override that is filled in by
     * the various subclasses.  It fills either the byte or char
     * array with a block of data from the input source.  As this
     * is a virtual function the right version will get called for
     * each source type.  Since it is only called once per block,
     * and from then on the final method which pulls data from
     * the block can return the value this should be a reasonable
     * performance trade off.
     * @return first value from the refilled input source or EOF
     * @throws IOException
     */
    protected int refill() throws IOException
    {
        UnifiedDataPageX  curr = _buffer.getCurrentPage();
        SavePoint sp = _save_points.savePointActiveTop();

        if (!can_fill_new_page()) {
            // aka: there can be only one!
            // (and it's used up)
            return refill_is_eof();
        }

        if (sp != null && sp.getEndIdx() == _buffer.getCurrentPageIdx()) {
            // also EOF but the case is odd enough to call it out
            return refill_is_eof();
        }

        long file_position;
        int start_pos = UNREAD_LIMIT;
        if (curr == null) {
            file_position = 0;
            start_pos = 0;
        }
        else {
            file_position = curr.getFilePosition(_pos);
            if (file_position == 0) {
                // unread before the beginning of file is not allowed,
                // so we don't have to leave room for it
                start_pos = 0;
            }
        }

        // see if we are re-reading saved buffers
        int new_idx = _buffer.getNextFilledPageIdx();
        if (new_idx < 0) {
            // there is no pre-filled page waiting for us, so we need to
            // read new data on a new page or over our current page
            curr = _buffer.getCurrentPage();
            boolean needs_new_page = (curr == null);
            new_idx = _buffer.getCurrentPageIdx();
            if (_save_points.isSavePointOpen()) {
                new_idx++;
                needs_new_page = true;
            }
            if (needs_new_page) {
                curr = _buffer.getEmptyPageIdx();
            }
            //
            //  here we actually read data into our buffers -----
            //
            int read = load(curr, start_pos, file_position);
            if (read < 1) {
                return refill_is_eof();
            }

            assert(curr != null && curr.getOffsetOfFilePosition(file_position) == start_pos);
            set_current_page(new_idx, curr, start_pos);
        }
        else {
            assert(!isEOF());
            if (sp != null && sp.getEndIdx() < new_idx/*_buffer.getCurrentPageIdx()*/) {
                return refill_is_eof();
            }

            curr = _buffer.getPage(new_idx);
            assert(curr.getStartingFileOffset() == file_position);

            set_current_page(new_idx, curr, curr.getStartingOffset());

            if (sp != null && sp.getEndIdx() == new_idx /*_buffer.getCurrentPageIdx()*/ ) {
                // the last page in the marked range will probably
                // require a different limit
                _limit = sp.getEndPos();
            }
        }

        assert(isEOF() ^ (_limit > 0));  // xor: either we're at eof or we have data to read
        return _limit;
    }
    private void set_current_page(int new_page_idx, UnifiedDataPageX new_page, int pos)
    {
        assert(new_page != null && new_page_idx >= 0 && new_page_idx <= _buffer.getPageCount() + 1);

        UnifiedDataPageX curr = null;
        if (new_page_idx < _buffer.getPageCount()) {
            curr = _buffer.getPage(new_page_idx);
        }

        if (new_page != curr) {
            _buffer.setPage(new_page_idx, new_page, true);
        }

        make_page_current(new_page, new_page_idx, pos, new_page.getBufferLimit());

        return;
    }
    private int refill_is_eof() {
        _eof = true;
        _limit = -1;
        return _limit;
    }

    private final boolean can_fill_new_page() {
        return _is_stream;
    }

    protected final int load(UnifiedDataPageX curr, int start_pos, long file_position) throws IOException
    {
        int read = 0;
        if (can_fill_new_page()) {
            if (is_byte_data()) {
                read = curr.load(_stream, start_pos, file_position);
            }
            else {
                read = curr.load(_reader, start_pos, file_position);
            }
        }
        return read;
    }

    //
    // specialized subclasses that provide an appropriate constructor
    // and refill method tailored to efficiently use the data source
    //
    private static class FromCharArray extends UnifiedInputStreamX
    {
        FromCharArray(CharSequence chars, int offset, int length)
        {
            _is_byte_data = false;
            _is_stream = false;
            _buffer = UnifiedInputBufferX.makePageBuffer(chars, offset, length);
            UnifiedDataPageX curr = _buffer.getCurrentPage();
            make_page_current(curr, 0, offset, offset+length);
            super.init();
        }
        FromCharArray(char[] charArray, int offset, int length)
        {
            _is_byte_data = false;
            _is_stream = false;
            _buffer = UnifiedInputBufferX.makePageBuffer(charArray, offset, length);
            UnifiedDataPageX curr = _buffer.getCurrentPage();
            make_page_current(curr, 0, offset, offset+length);
            super.init();
        }
    }

    private static class FromCharStream extends UnifiedInputStreamX
    {
        FromCharStream(Reader reader) throws IOException
        {
            _is_byte_data = false;
            _is_stream = true;
            _reader = reader;
            _buffer = UnifiedInputBufferX.makePageBuffer(UnifiedInputBufferX.BufferType.CHARS, DEFAULT_PAGE_SIZE);
            super.init();
            _limit = refill();
        }
    }
    private static class FromByteArray extends UnifiedInputStreamX
    {
        FromByteArray(byte[] bytes, int offset, int length)
        {
            _is_byte_data = true;
            _is_stream = false;
            _buffer = UnifiedInputBufferX.makePageBuffer(bytes, offset, length);
            UnifiedDataPageX curr = _buffer.getCurrentPage();
            make_page_current(curr, 0, offset, offset+length);
            super.init();
        }
    }

    private static class FromByteStream extends UnifiedInputStreamX
    {
        FromByteStream(InputStream stream) throws IOException
        {
            _is_byte_data = true;
            _is_stream = true;
            _stream = stream;
            _buffer = UnifiedInputBufferX.makePageBuffer(UnifiedInputBufferX.BufferType.BYTES, DEFAULT_PAGE_SIZE);
            super.init();
            _limit = refill();
        }
    }
}
