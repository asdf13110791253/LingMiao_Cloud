#!/bin/bash
# 灵喵 LingMiao — 一键初始化GitHub仓库 + 触发云端构建
# 用法：./init-repo.sh <你的GitHub用户名> <仓库名>
#
# 示例：./init-repo.sh zhangsan LingMiao
#
# 执行后：
# 1. 在GitHub创建仓库（需要gh CLI登录）
# 2. 推送代码
# 3. GitHub Actions自动编译 → 签名 → 出APK
# 4. 约5~8分钟后APK可下载

set -e

USERNAME="$1"
REPO="$2"

if [ -z "$USERNAME" ] || [ -z "$REPO" ]; then
    echo "用法: ./init-repo.sh <GitHub用户名> <仓库名>"
    echo "示例: ./init-repo.sh zhangsan LingMiao"
    exit 1
fi

echo "🚀 初始化灵喵 LingMiao 云端仓库..."
echo "   用户: $USERNAME"
echo "   仓库: $REPO"
echo ""

# 检查gh CLI
if ! command -v gh &> /dev/null; then
    echo "❌ 需要安装 GitHub CLI: https://cli.github.com/"
    echo "   安装后运行: gh auth login"
    exit 1
fi

# 进入项目目录
cd "$(dirname "$0")"

# 初始化git
if [ ! -d .git ]; then
    git init
    git branch -M main
fi

# 创建GitHub仓库
echo "📦 在GitHub创建仓库..."
gh repo create "$USERNAME/$REPO" --public --source=. --push || true

# 设置密钥（默认密码）
echo "🔑 设置签名密钥到GitHub Secrets..."
gh secret set KEYSTORE_PASSWORD -b"LingMiao2024!" --repo "$USERNAME/$REPO"
gh secret set KEY_PASSWORD -b"LingMiao2024!" --repo "$USERNAME/$REPO"

# 添加远程
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$USERNAME/$REPO.git"

# 提交并推送
git add .
git commit -m "🐱 灵喵 LingMiao v2.0 — 全平台通用台球辅助

✅ 自动最优路线判定（直球/反带/多库翻袋）
✅ 桌布区域四角独立拖拽 + 透视矫正
✅ 自适应球桌大小（540p~2K）
✅ 全平台通用（大型/小型/抖音小窗）
✅ 云端自动构建 + 签名
✅ 目标体积 22MB" 2>/dev/null || true

git push -u origin main

echo ""
echo "✅ 代码已推送！"
echo ""
echo "🔗 打开以下链接查看构建进度："
echo "   https://github.com/$USERNAME/$REPO/actions"
echo ""
echo "⏱ 预计 5~8 分钟后APK可下载"
echo "   下载路径: Actions → 最新任务 → Artifacts"
echo ""
echo "📱 APK安装后记得授权：悬浮窗 + 录屏 + 电池白名单"
