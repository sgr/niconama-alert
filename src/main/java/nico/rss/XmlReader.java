// -*- coding: utf-8-unix -*-
package nico.rss;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

public class XmlReader extends InputStreamReader {
    public XmlReader(InputStream is) throws java.io.UnsupportedEncodingException {
	super(is, "UTF-8");
    }

    public XmlReader(InputStream is, Charset cs) throws java.io.UnsupportedEncodingException {
	super(is, "UTF-8");	// ignore cs
    }

    public XmlReader(InputStream is, CharsetDecoder dec) throws java.io.UnsupportedEncodingException {
	super(is, "UTF-8");	// ignore dec
    }

    public XmlReader(InputStream is, String charsetName) throws java.io.UnsupportedEncodingException {
	super(is, "UTF-8");	// ignore charsetName
    }

    private static boolean valid(int c) {
	return ((c == 0x9) ||
		(c == 0xA) ||
		(c == 0xD) ||
		((c >= 0x20) && (c <= 0xD7FF)) ||
		((c >= 0xE000) && (c <= 0xFFFD)) ||
		((c >= 0x10000) && (c <= 0x10FFFF)));
    }

    public int read() throws IOException {
	int c;
	while ((c = super.read()) != -1) {
	    if (valid(c)) {
		return c;
	    }
	}
	return c;
    }

    public int read(char[] cbuf, int offset, int length) throws IOException {
	char[] tbuf = new char[length];
	int len = super.read(tbuf, 0, length);
	if (len > 0) {
	    int pos = 0;
	    for (int i = 0; i < len; i++) {
		if (valid(tbuf[i])) {
		    cbuf[offset + pos] = tbuf[i];
		    pos++;
		}
	    }
	    return pos;
	} else {
	    return len;
	}
    }
}
