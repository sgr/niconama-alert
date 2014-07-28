---
layout: default
title: niconama-alert.clj download
---

こちらよりお使いのOS用のバイナリをダウンロードします。
対応OS用のバイナリがない場合は、[ソースコードからのビルド](#howtobuild)をご覧ください。

* [1.7.1 Windows 用インストーラー](https://docs.google.com/uc?export=download&id=0BwIJLE1B4O3mRWd0OUhOLUxBUW8) (Windows 7で動作確認)
* [1.7.1 Mac OS X 用 DMG イメージ](https://docs.google.com/uc?export=download&id=0BwIJLE1B4O3mTmtqV0Nja0FkNzg) (Mac OS X 10.9.2で動作確認)

補足 (Mac OS)
==============

Mac OS X 10.9 でディスクイメージからApplicationsへコピーした後、実行しようとすると

> “NicoNamaAlert”は壊れているため開けません。“ゴミ箱”に入れる必要があります。

というダイアログが表示される場合があります。
このような場合は、Apple メニュー >「システム環境設定」>「セキュリティとプライバシー」>「一般」タブの
「ダウンロードしたアプリケーションの実行許可」で「すべてのアプリケーションを許可」を
__一時的__に設定することで起動できるようになります。これは初回起動時だけ必要です。

また、初回起動時に

> “NicoNamaAlert”を開くには、Java SE 6 ランタイムが必要です。今すぐインストールしますか？

というダイアログが表示される場合があります。
「インストール」ボタンをクリックし、Javaランタイムをインストールすると起動できるようになります。


ソースコードからのビルド{#howtobuild}
=====================================

Windows や Mac OS 以外のOSでも、ソースコードをダウンロードしビルドすることが可能です。
ビルドには [JDK](http://www.oracle.com/technetwork/java/javase/downloads/index.html) と
[leiningen](http://leiningen.org/) が必要です。

ソースコードは [こちら](https://github.com/sgr/niconama-alert) より入手できます。

ソースコードを入手したら、ソースコードのトップディレクトリで lein コマンドでビルドを実行します。

{% highlight sh %}
lein uberjar
{% endhighlight %}

ビルドが完了したら、次のようにして実行することが出来ます。

{% highlight sh %}
java -jar target/nicoalert-(バージョン)-standalone.jar
{% endhighlight %}
