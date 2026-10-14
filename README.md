app-core 폴더에서
```cargo test -- --nocapture
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ../TBCC/app/src/main/jniLibs build --release
cargo run --manifest-path ../uniffi-bindgen/Cargo.toml -- generate --library target/aarch64-linux-android/release/libapp_core.so --language kotlin --out-dir ../TBCC/app/src/main/java --config uniffi.toml
```

100% 바이브 코딩이라 저도 뭔지 모름 수고
