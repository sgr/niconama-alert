// -*- coding: utf-8-unix -*-
package com.github.sgr.util;
import java.awt.Image;
import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.ImageIcon;

/**
 * URLで示される画像のキャッシュ。
 * キャッシュに存在しない画像はURLをもとに取得するため、
 * このクラスには登録メソッドが存在しない。
 */
public class ImageCache {
    private static final Logger log = Logger.getLogger("com.github.sgr.util");
    private static final String NOIMAGE = "noimage.png";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 8000;
    private static final int LIMIT_FETCH = 5;
    private static final int INTERVAL_REFETCH = 1;

    private Toolkit _tk = Toolkit.getDefaultToolkit();
    private Image _fallbackImage = null; // 取得失敗時に仮に表示するアイコン画像

    private LinkedHashMap<String, Image> _map = null;

    /**
     * @param capacity キャッシュの最大保持数
     * @param fallbackImage イメージ取得に失敗した際に代わりに格納する画像
     */
    public ImageCache(final int capacity, Image fallbackImage) {
	_map = new LinkedHashMap<String, Image>(capacity + 1, 1.1f, true) {
	    @Override
	    protected boolean removeEldestEntry(final Map.Entry<String, Image> entry) {
		if (super.size() > capacity) {
		    entry.getValue().flush();
		    return true;
		} else {
		    return false;
		}
	    }
	};
	_fallbackImage = fallbackImage;
    }

    public synchronized Image getImage(String url) throws MalformedURLException {
	Image img = _map.get(url);
	if (img == null) {
	    img = fetch(new URL(url));
	    if (img != null) {
		_map.put(url, img);
	    }
	}
	return img != null ? img : _fallbackImage;
    }

    public ImageIcon getImageIcon(String url) throws MalformedURLException {
	return new ImageIcon(getImage(url));
    }

    /**
     * URLで示された画像データを取得する。
     * 通信エラーによる失敗があっても {@ImageCache#LIMIT_FETCH} 回試行する。
     * 再試行のインターバルは {@ImageCache#INTERVAL_REFETCH} 秒である。
     *
     * @param url 取得する画像を指すURL文字列
     * @return 取得に成功した場合はImage、失敗した場合はnull
     */
    private Image fetch(URL url) {
	for (int i = 0; i < LIMIT_FETCH; i++) {
	    Image img = fetchAux(url);
	    if (img != null) {
		return img;
	    }
	    try {
		TimeUnit.SECONDS.sleep(INTERVAL_REFETCH);
	    } catch (Exception _) { }
	}
	return null;
    }

    /**
     * ImageCache#fetch の下請けメソッド。
     *
     * @param url 取得する画像を指すURL文字列
     * @return 取得に成功した場合はImage、失敗した場合はnull
     */
    private Image fetchAux(URL url) {
	HttpURLConnection conn = null;
	try {
	    conn = (HttpURLConnection)url.openConnection();
	    conn.setConnectTimeout(CONNECT_TIMEOUT);
	    conn.setReadTimeout(READ_TIMEOUT);
	    return imageFromStream(conn.getInputStream());
	} catch (SocketTimeoutException e) {
	    String errMsg = "timed out for " + url;
	    log.log(Level.SEVERE, errMsg);
	    return null;
	} catch (IOException e) {
	    try {
		String errMsg = String.format("caught HTTP error response[%d] for %s", (conn.getResponseCode()), url);
		log.log(Level.SEVERE, errMsg);
	    } catch (IOException _) {
		// nothing to do
	    }
	    return null;
	}
    }

    private Image imageFromStream(InputStream is) throws IOException {
	ByteArrayOutputStream baos = new ByteArrayOutputStream();
	byte[] buf = new byte[1024];
	int len = -1;
	while ((len = is.read(buf)) >= 0) {
	    baos.write(buf, 0, len);
	}
//	Image img = _tk.createImage(baos.toByteArray());
//	return img.getScaledInstance(48, 48, Image.SCALE_SMOOTH);
	return _tk.createImage(baos.toByteArray());
    }
}
