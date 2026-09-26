#!/usr/bin/env bash
# 在你自己的服务器上运行：把噜噜传声用到的语音识别模型全部下载到一个目录，
# 再用 nginx 等把这个目录对外提供下载。
#
# 用法： bash mirror_models.sh [保存目录]      默认保存到 /var/www/vosk
set -euo pipefail

DIR="${1:-/var/www/vosk}"
MODELS=(
  vosk-model-small-cn-0.22
  vosk-model-small-en-us-0.15
  vosk-model-small-es-0.42
  vosk-model-small-pt-0.3
  vosk-model-small-ru-0.22
  vosk-model-small-fr-0.22
  vosk-model-small-de-0.15
  vosk-model-small-it-0.22
  vosk-model-small-ja-0.22
  vosk-model-small-ko-0.22
  vosk-model-small-vn-0.4
  vosk-model-small-tr-0.3
  vosk-model-small-hi-0.22
)

mkdir -p "$DIR"
for m in "${MODELS[@]}"; do
  if [ -f "$DIR/$m.zip" ]; then
    echo "已存在，跳过：$m.zip"
    continue
  fi
  echo "下载：$m.zip"
  curl -fL --retry 3 -o "$DIR/$m.zip.part" "https://alphacephei.com/vosk/models/$m.zip"
  mv "$DIR/$m.zip.part" "$DIR/$m.zip"
done

echo
echo "完成，共 $(ls "$DIR"/*.zip | wc -l) 个模型，占用 $(du -sh "$DIR" | cut -f1)，保存在 $DIR"
echo "测试下载地址是否可用：curl -I http://你的服务器地址/vosk/vosk-model-small-en-us-0.15.zip"
