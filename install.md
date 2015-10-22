---
layout: default
title: niconama-alert.clj download
---

導入時の注意点 (Mac OS 10.11 El Capitan) {#notice_elcapitan}
===============================================

Mac OS X 10.11 でディスクイメージからApplicationsへコピーした後、実行しようとすると

![“NicoNamaAlert”は壊れているため開けません。“ゴミ箱”に入れる必要があります。]({{ site.baseurl }}/images/warn_gk.png)

というダイアログが表示される場合があります。
このような場合は、Apple メニュー >「システム環境設定」>「セキュリティとプライバシー」>「一般」タブの
「ダウンロードしたアプリケーションの実行許可」で「すべてのアプリケーションを許可」を
__一時的__ に設定することで起動できるようになります。これは初回起動時だけ必要です。

また、初回起動時に
 
![“NicoNamaAlert”を開くには、以前の Java SE 6 ランタイムをインストールする必要があります。]({{ site.baseurl }}/images/warn_yosemite.png)

というダイアログが表示される場合があります。
「詳しい情報...」ボタンをクリックすると開くページからJavaランタイムをダウンロードし、
インストールすると起動できるようになります。


導入時の注意点 (Mac OS 10.10 Yosemite) {#notice_yosemite}
===============================================

Mac OS X 10.10 でディスクイメージからApplicationsへコピーした後、実行しようとすると

![“NicoNamaAlert”は壊れているため開けません。“ゴミ箱”に入れる必要があります。]({{ site.baseurl }}/images/warn_gk.png)

というダイアログが表示される場合があります。
このような場合は、Apple メニュー >「システム環境設定」>「セキュリティとプライバシー」>「一般」タブの
「ダウンロードしたアプリケーションの実行許可」で「すべてのアプリケーションを許可」を
__一時的__ に設定することで起動できるようになります。これは初回起動時だけ必要です。

また、初回起動時に
 
![“NicoNamaAlert”を開くには、以前の Java SE 6 ランタイムをインストールする必要があります。]({{ site.baseurl }}/images/warn_yosemite.png)

というダイアログが表示される場合があります。
「詳しい情報...」ボタンをクリックすると開くページからJavaランタイムをダウンロードし、
インストールすると起動できるようになります。


導入時の注意点 (Mac OS 10.9 Mavericks) {#notice_mavericks}
================================================

Mac OS X 10.9 でディスクイメージからApplicationsへコピーした後、実行しようとすると

![“NicoNamaAlert”は壊れているため開けません。“ゴミ箱”に入れる必要があります。]({{ site.baseurl }}/images/warn_gk.png)

というダイアログが表示される場合があります。
このような場合は、Apple メニュー >「システム環境設定」>「セキュリティとプライバシー」>「一般」タブの
「ダウンロードしたアプリケーションの実行許可」で「すべてのアプリケーションを許可」を
__一時的__ に設定することで起動できるようになります。これは初回起動時だけ必要です。

また、初回起動時に

> “NicoNamaAlert”を開くには、Java SE 6 ランタイムが必要です。今すぐインストールしますか？

というダイアログが表示される場合があります。
「インストール」ボタンをクリックし、Javaランタイムをインストールすると起動できるようになります。


補足：ソースコードからのビルド {#howtobuild}
======================================

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
