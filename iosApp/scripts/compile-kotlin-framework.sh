#!/bin/sh
#
# 编译并嵌入 Kotlin/Native 产出的 ComposeApp.framework。
# 由 Xcode 的 "Compile Kotlin Framework" 构建阶段调用，位置在 Compile Sources **之前**。
#
# 为什么这里要自带一段"找 JDK"的逻辑，而不是在 Run Script 里直接写 ./gradlew：
#
#   Xcode 的构建环境**不继承你的 shell 配置**（不读 .zshrc / .zprofile），
#   所以 JAVA_HOME 往往是空的，gradlew 只好退回到 PATH 上碰巧存在的 java。
#   本机 PATH 上的 java 是 JDK 8，而 Gradle 8 + KGP + AGP 需要 JDK 11+，
#   于是构建失败并报：
#     Dependency requires at least JVM runtime version 11. This build uses a Java 8 JVM.
#
#   这个报错看起来像"项目依赖配置有问题"，实际只是"没找到合适的 JDK"——
#   是个很容易查错方向的坑，所以在这里一次性挡住。
#
set -e

# KMP 模板的标准逃生口：IDE 想跳过 Gradle 调用时置这个变量。
if [ "YES" = "$OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED" ]; then
  echo "跳过 Gradle 调用：OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES"
  exit 0
fi

# 判断某个 JAVA_HOME 是否指向一个 >= 11 的 JDK。
# 注意 "1.8.0_xxx"（JDK 8）不该匹配 —— 正则要求主版本号是 11..99 或 2 位数以上。
jdk_is_new_enough() {
  [ -n "$1" ] && [ -x "$1/bin/java" ] || return 1
  "$1/bin/java" -version 2>&1 | grep -qE 'version "(1[1-9]|[2-9][0-9])'
}

# 优先级：已有的可用 JAVA_HOME → Android Studio 自带 JBR → java_home 依次查 21 / 17 / 11。
if ! jdk_is_new_enough "$JAVA_HOME"; then
  for candidate in \
      "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
      "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
      "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
      "$(/usr/libexec/java_home -v 11 2>/dev/null || true)"
  do
    if jdk_is_new_enough "$candidate"; then
      export JAVA_HOME="$candidate"
      echo "→ JAVA_HOME 未设置或版本过低，改用：$JAVA_HOME"
      break
    fi
  done
fi

if ! jdk_is_new_enough "$JAVA_HOME"; then
  echo "error: 找不到 JDK 11+，Gradle 无法运行。" >&2
  echo "       装一个 JDK 或 Android Studio；或把下面这行写进 ~/.gradle/gradle.properties：" >&2
  echo "         org.gradle.java.home=/path/to/jdk-21" >&2
  exit 1
fi

cd "$SRCROOT/.."
exec ./gradlew :composeApp:embedAndSignAppleFrameworkForXcode
