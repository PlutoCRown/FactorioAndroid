# TLS 适配源码

基础来源为 taowen/libhybris 的 Apache-2.0 TLS 实现：
https://github.com/taowen/libhybris/blob/2375acd0b5d278a0a2f745c645680545e8f80539/hybris/common/bionic_tls.c

本目录四个实际输入文件（bionic_tls.c、bionic_tls.h、logging.h、config.h）包含本项目使用的静态 TLS 容量、范围检查与线程初始化适配，不依赖目录外的未提交源码。保留原版权声明。编译命令及 APK 接入见 docs/SOURCE-BUILD.md，自动入口为 scripts/BuildImeNative.java。
