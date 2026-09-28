#!/bin/bash
set -e
WS="/home/user/.doubao/agent_mode/workspace/.sessions/38441291084638978"
export JAVA_HOME="$WS/toolchain/jdk17"
export ANDROID_HOME="$WS/toolchain/sdk"
BT=$ANDROID_HOME/build-tools/34.0.0
export PATH=$JAVA_HOME/bin:$BT:$PATH
cd "$WS/flowapp"
echo "== 1/6 javac =="
rm -rf obj dex build && mkdir -p obj dex build
javac -classpath $ANDROID_HOME/platforms/android-34/android.jar -d obj src/com/cmcc/flowapp/*.java
echo "== 2/6 d8 =="
d8 --release --lib $ANDROID_HOME/platforms/android-34/android.jar --output dex $(find obj -name '*.class')
echo "== 3/6 aapt2 compile =="
aapt2 compile --dir res -o build/res.zip
echo "== 4/6 aapt2 link =="
aapt2 link -o build/base.apk -I $ANDROID_HOME/platforms/android-34/android.jar --manifest AndroidManifest.xml build/res.zip --auto-add-overlay
echo "== 5/6 dex+align =="
(cd dex && zip -q ../build/base.apk classes.dex)
zipalign -f 4 build/base.apk build/aligned.apk
echo "== 6/6 sign =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore -alias debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks debug.keystore --ks-key-alias debug --ks-pass pass:android --key-pass pass:android --out "build/流量助手.apk" build/aligned.apk
cp "build/流量助手.apk" "/home/user/Doubao/chats/38441291084638978/流量助手.apk"
echo "BUILD OK -> /home/user/Doubao/chats/38441291084638978/流量助手.apk"
