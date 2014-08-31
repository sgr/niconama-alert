// -*- coding: utf-8-unix -*-
package nico.cache;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.HttpURLConnection;
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
import javax.swing.ImageIcon;

/**
 * URLで示される画像のキャッシュ。
 * キャッシュに存在しない画像はURLをもとに取得するため、
 * このクラスには登録メソッドが存在しない。
 */
public class ImageCache {
    private static final Logger log = Logger.getLogger(ImageCache.class.getCanonicalName());
    private static final int LIMIT_REFETCH = 5;
    private static final int INTERVAL_REFETCH = 2;
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
		    entry.getValue().flush();
		    log.log(Level.FINE, MessageFormat.format("removeEldestEntry: {0} > {1}", super.size(), capacity));
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
				log.log(Level.WARNING, MessageFormat.format("Malformed URL string: {0}", url));
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

	    try {
		byte[] data = fetchData(url);
		if (data != null) {
		    return createImage(data);
		}
	    } catch (FileNotFoundException e) {
		return null; // 直ちに終了
	    }
	}
	return null;
    }

    /**
     * ImageCache#fetch の下請けメソッド。
     *
     * @param url 取得する画像を指すURL文字列
     * @return 取得に成功した場合はイメージデータバイト列、失敗した場合はnull
     * @throws FileNotFoundException URLで示されるリソースが存在しない場合
     */
    private byte[] fetchData(URL url) throws FileNotFoundException {
	try {
	    URLConnection conn = url.openConnection();
	    conn.setConnectTimeout(1000);
	    conn.setReadTimeout(FETCHING_TIMEOUT_SEC * 1000);
	    conn.connect();
	    InputStream is = conn.getInputStream();
	    ByteArrayOutputStream os = new ByteArrayOutputStream();
	    byte[] tmpBuf = new byte[1024];
	    int len = -1;
	    try {
		while (0 <= (len = is.read(tmpBuf))) {
		    os.write(tmpBuf, 0, len);
		}
	    } catch (Exception e) {
		log.log(Level.WARNING, MessageFormat.format("failed reading data from: {0} ({1})", url.toString(), e.getMessage()));
		os.reset();
	    } finally {
		is.close();
		if (conn instanceof HttpURLConnection) {
		    ((HttpURLConnection)conn).disconnect();
		}
	    }
	    if (os.size() > 0) {
		return os.toByteArray();
	    } else {
		return null;
	    }
	} catch (FileNotFoundException e) {
	    log.log(Level.FINE, MessageFormat.format("requested resource is not found: {0}", url.toString()));
	    throw e;
	} catch (Exception e) {
	    log.log(Level.WARNING, MessageFormat.format("failed connecting the resource: {0} ({1})", url.toString(), e.getMessage()));
	    return null;
	}
    }

    /**
     * イメージデータバイト列から画像を生成する。
     * 画像が決められたサイズより大きい場合は収まるようにスケールされる。
     *
     * @param イメージデータバイト列
     * @return 生成に成功した場合はImage、失敗した場合はnull
     */
    private Image createImage(byte[] data) {
	try {
	    Image img = _tk.createImage(data);
	    int handle = img.hashCode();
	    _mt.addImage(img, handle);
	    try {
		_mt.waitForID(handle);
		if ((img != null) &&
		    (img.getWidth(null) > _width) ||
		    (img.getHeight(null) > _height)) {
		    // try {
		    // 	BufferedImage scaledImg = new BufferedImage(_width, _height, BufferedImage.TYPE_INT_ARGB);
		    // 	Graphics2D g = scaledImg.createGraphics();
		    // 	g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
		    // 	g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		    // 	g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
		    // 	g.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
		    // 	g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		    // 	g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		    // 	g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		    // 	g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		    // 	g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
		    // 	g.drawImage(img, 0, 0, _width, _height, null);
		    // 	log.log(Level.FINE,
		    // 		MessageFormat.format("scaled image: ({0}, {1}) -> ({2}, {3})",
		    // 				     img.getWidth(null), img.getHeight(null),
		    // 				     scaledImg.getWidth(null), scaledImg.getHeight(null)));
		    // 	g.dispose();
		    // 	img.flush();
		    // 	return scaledImg;
		    // } catch (Exception e) {
		    // 	log.log(Level.SEVERE,
		    // 		MessageFormat.format("failed scaling image: ({0}, {1})",
		    // 				     img.getWidth(null), img.getHeight(null)),
		    // 		e);
		    // 	return img;
		    // }
		    return img.getScaledInstance(_width, _height, Image.SCALE_AREA_AVERAGING);
		} else {
		    return img;
		}
	    } catch (Exception e) {
		log.log(Level.SEVERE, MessageFormat.format("failed creating image from bytes[{0}]", data.length), e);
		return null;
	    } finally {
		_mt.removeImage(img);
	    }
	} catch (Exception e) {
	    log.log(Level.WARNING, MessageFormat.format("failed fetching image from bytes [{0}] ({1})", data.length, e.getMessage()));
	    return null;
	}
    }
}
