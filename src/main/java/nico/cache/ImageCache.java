// -*- coding: utf-8-unix -*-
package nico.cache;
import java.awt.Container;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.net.URL;
import java.net.URLConnection;
import java.net.MalformedURLException;
import java.text.MessageFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * URLで示される画像のキャッシュ。
 * キャッシュに存在しない画像はURLをもとに取得するため、
 * このクラスには登録メソッドが存在しない。
 */
public class ImageCache {
    private static final Logger log = Logger.getLogger(ImageCache.class.getCanonicalName());
    private static final int LIMIT_REFETCH = 5;
    private static final int INTERVAL_REFETCH = 5;
    private static final int FETCHING_TIMEOUT_SEC = 10;

    private Toolkit _tk = Toolkit.getDefaultToolkit();
    private MediaTracker _mt = new MediaTracker(new Container());
    private Image _fallbackImage = null; // 取得失敗時に仮に表示するアイコン画像
    private int _limitRefetch = LIMIT_REFETCH;
    private int _intervalRefetch = INTERVAL_REFETCH;
    private int _width = -1;
    private int _height = -1;

    // 取得処理の管理
    private LinkedBlockingQueue<Runnable> _queue = new LinkedBlockingQueue<Runnable>();
    private ThreadPoolExecutor _executor = new ThreadPoolExecutor(0, 3, 3, TimeUnit.SECONDS, _queue);
    private HashMap<String, Future<Image>> _fetching = new HashMap<String, Future<Image>>();

    // 取得されたイメージの管理
    private LinkedHashMap<String, Image> _map = null;

    /**
     * @param capacity キャッシュの最大保持数
     * @param limitRefetch イメージ再取得回数上限
     * @param intervalRefetch イメージ再取得間隔(秒)
     * @param width イメージの最大幅
     * @param height イメージの最大高
     * @param fallbackImage イメージ取得に失敗した際に代わりに返す画像
     */
    public ImageCache(final int capacity, int limitRefetch, int intervalRefetch, int width, int height, Image fallbackImage) {
	_map = new LinkedHashMap<String, Image>(capacity + 1, 1.1f, true) {
	    @Override
	    protected boolean removeEldestEntry(final Map.Entry<String, Image> entry) {
		if (super.size() > capacity) {
		    //entry.getValue().flush(); // 今は利用側でフラッシュするためコメントアウト
		    //log.log(Level.FINEST, MessageFormat.format("removeEldestEntry: {0} > {1}", super.size(), capacity));
		    return true;
		} else {
		    return false;
		}
	    }
	};
	_limitRefetch = limitRefetch;
	_intervalRefetch = intervalRefetch;
	_width = width;
	_height = height;
	_fallbackImage = fallbackImage;
    }

    /**
     * @param capacity キャッシュの最大保持数
     * @param width イメージの最大幅
     * @param height イメージの最大高
     * @param fallbackImage イメージ取得に失敗した際に代わりに返す画像
     */
    public ImageCache(final int capacity, int width, int height, Image fallbackImage) {
	this(capacity, LIMIT_REFETCH, INTERVAL_REFETCH, width, height, fallbackImage);
    }

    public Image getImage(final String url) {
	Image img = null;
	synchronized (_map) {
	    img = _map.get(url);
	}
	if (img != null) {
	    return img;
	} else {
	    Future<Image> f = null;
	    synchronized (_fetching) {
		f = _fetching.get(url);
	    }
	    if (f == null) {
		f = _executor.submit(new Callable<Image>() {
			public Image call() {
			    try {
				return fetch(new URL(url));
			    } catch (MalformedURLException e) {
				log.log(Level.WARNING, MessageFormat.format("Malformed URL string: {0}", url), e);
				return null;
			    }
			}
		    });
		synchronized (_fetching) {
		    _fetching.put(url, f);
		}
		try {
		    img = f.get(); // 最初にリクエストした者は最後まで待つ
		} catch (CancellationException e) {
		    log.log(Level.WARNING, "CANCELED while fetching image, queue size: {0}", _queue.size());
		} catch (ExecutionException e) {
		    log.log(Level.WARNING, "THROWN EXCEPTION while fetching image, queue size: {0}", _queue.size());
		} catch (InterruptedException e) {
		    log.log(Level.WARNING, "INTERRUPTED while fetching image, queue size: {0}", _queue.size());
		} finally {
		    synchronized (_fetching) {
			_fetching.remove(url);
		    }
		    if (img != null) {
			synchronized (_map) {
			    _map.put(url, img);
			}
		    }
		}
	    } else {
		try {
		    img = f.get(FETCHING_TIMEOUT_SEC, TimeUnit.SECONDS);
		} catch (CancellationException e) {
		    log.log(Level.WARNING, "CANCELED while fetching image, queue size: {0}", _queue.size());
		} catch (ExecutionException e) {
		    log.log(Level.WARNING, "THROWN EXCEPTION while fetching image, queue size: {0}", _queue.size());
		} catch (InterruptedException e) {
		    log.log(Level.WARNING, "INTERRUPTED while fetching image, queue size: {0}", _queue.size());
		} catch (TimeoutException e) {
		    log.log(Level.WARNING, "TIMEOUTED while fetching image, queue size: {0}", _queue.size());
		}
	    }

	    return img != null ? img : _fallbackImage;
	}
    }

    public ImageIcon getImageIcon(String url) {
	try {
	    Image img = getImage(url);
	    return new ImageIcon(img);
	} catch (Exception e) {
	    log.log(Level.SEVERE, MessageFormat.format("failed creating ImageIcon: {0}", url.toString()), e);
	    return new ImageIcon(_fallbackImage);
	}
    }

    /**
     * URLで示された画像データを取得する。
     *
     * @param url 取得する画像を指すURL文字列
     * @return 取得に成功した場合はImage、失敗した場合はnull
     */
    private Image fetch(URL url) {
	for (int i = 0; i <= _limitRefetch; i++) {
	    if (i > 0) {
		try {
		    TimeUnit.SECONDS.sleep(_intervalRefetch);
		} catch (Exception _) { }
	    }

	    Image img = fetchAux(url);
	    if (img != null) {
		return img;
	    }
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
	try {
	    Image img = _tk.createImage(url);
	    int handle = img.hashCode();
	    _mt.addImage(img, handle);
	    try {
		_mt.waitForID(handle, FETCHING_TIMEOUT_SEC * 1000);
		if (img == null) {
		    URLConnection conn = url.openConnection();
		    conn.setConnectTimeout(1000);
		    conn.setReadTimeout(3000);
		    conn.connect();
		    img = ImageIO.read(conn.getInputStream());
		}
		if ((img != null) &&
		    (img.getWidth(null) > _width) ||
		    (img.getHeight(null) > _height)) {
		    Image scaledImg = img.getScaledInstance(_width, _height, Image.SCALE_DEFAULT);
		    int scaledHandle = img.hashCode();
		    _mt.addImage(scaledImg, scaledHandle);
		    try {
			_mt.waitForID(scaledHandle);
			log.log(Level.FINE,
				MessageFormat.format("scaled image[{0}]: ({1}, {2}) -> ({3}, {4})",
						     url.toString(),
						     img.getWidth(null), img.getHeight(null),
						     scaledImg.getWidth(null), scaledImg.getHeight(null)));
			return scaledImg;
		    } catch (Exception e) {
			log.log(Level.WARNING,
				MessageFormat.format("failed scaling image[{0}]: ({1}, {2})",
						     url.toString(),
						     img.getWidth(null), img.getHeight(null)),
				e);
			return img;
		    } finally {
			_mt.removeImage(scaledImg);
		    }
		} else {
		    return img;
		}
	    } catch (Exception e) {
		log.log(Level.WARNING, MessageFormat.format("failed creating image from: {0}", url.toString()), e);
		return null;
	    } finally {
		_mt.removeImage(img);
	    }
	} catch (Exception e) {
	    log.log(Level.WARNING, MessageFormat.format("failed fetching image from: {0}", url.toString()), e);
	    return null;
	}
    }
}
