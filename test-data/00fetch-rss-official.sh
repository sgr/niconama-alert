#!/bin/sh

RSS_OFFICIAL="http://live.nicovideo.jp/rss"
RSS_USER="http://live.nicovideo.jp/recent/rss?p="

case $PWD in
	*/test-data)
		echo "Fetch Official RSS"
		curl -O ${RSS_OFFICIAL}
		for n in `seq 1 180`; do
			curl -O ${RSS_USER}$n
		done
		;;
	*) echo "Change directory 'test-data', and run this script.";;
esac

