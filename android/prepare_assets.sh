#!/usr/bin/env bash
# 把仓库根目录的站点静态资源拷进 Android 封装工程的 assets/www。
#
# 站点本身是零构建的纯静态资源（无 npm build），直接复制即可。
# 会跳过仅用于 README 演示的 hero_demo_*.webp / preview_*.webp（约 17 MB）。
#
# 用法：在仓库根目录执行  bash android/prepare_assets.sh

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dst="$repo_root/android/app/src/main/assets/www"

cd "$repo_root"

rm -rf "$dst"
mkdir -p "$dst"

cp index.html manifest.webmanifest robots.txt sitemap.xml "$dst/"
cp -r css js data geojson topics "$dst/"

mkdir -p "$dst/assets"
cp -r assets/icons assets/terrain assets/audio "$dst/assets/"
[ -f assets/favicon.svg ] && cp assets/favicon.svg "$dst/assets/"
for f in assets/*.webp; do
  case "$(basename "$f")" in
    hero_demo_* | preview_*) continue ;;
  esac
  cp "$f" "$dst/assets/"
done

echo "已准备站点资源 -> $dst"
du -sh "$dst"
