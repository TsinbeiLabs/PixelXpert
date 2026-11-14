cp app\release\PixelXpert.apk MagiskModBase\PixelXpert.apk

cd MagiskModBase

zip -r -9 -q ..\PixelXpert.zip *.*

rm -Rf PixelXpert.apk
