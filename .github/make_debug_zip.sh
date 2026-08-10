#!/bin/bash

mkdir output
cp app/build/outputs/apk/debug/PixelXpert-signed.apk MagiskModBase/system/priv-app/TsinbeiPixelXpert/PixelXpert.apk
cd MagiskModBase

FILENAME="PixelXpert.zip"
zip -r ../output/$FILENAME *;
