use android_logger::Config;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use log::{error, info, LevelFilter};
use std::ptr;
use thiserror::Error;
use tokio::runtime::{Builder, Runtime};

const VERSION: &str = concat!("metrofuse-spotify-native/", env!("CARGO_PKG_VERSION"));

struct SpotifyRuntime {
    cache_dir: String,
    runtime: Runtime,
}

#[derive(Debug, Error)]
enum BridgeError {
    #[error("cache directory is empty")]
    EmptyCacheDir,
    #[error("failed to create Spotify runtime: {0}")]
    Runtime(std::io::Error),
}

impl SpotifyRuntime {
    fn new(cache_dir: String) -> Result<Self, BridgeError> {
        if cache_dir.trim().is_empty() {
            return Err(BridgeError::EmptyCacheDir);
        }

        let runtime = Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("metrofuse-spotify")
            .build()
            .map_err(BridgeError::Runtime)?;

        Ok(Self { cache_dir, runtime })
    }

    fn smoke_test(&self) -> bool {
        let _ = self.runtime.handle();
        !self.cache_dir.trim().is_empty()
    }
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Info)
            .with_tag("MetroFuseSpotify"),
    );
    info!("{VERSION} loaded");
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_metrolist_music_spotify_bridge_SpotifyBridge_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.create_string(VERSION) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                format!("Failed to create native version string: {error}"),
            );
            ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_metrolist_music_spotify_bridge_SpotifyBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
) -> jlong {
    let cache_dir = match env.get_string(&cache_dir) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => {
            throw_bridge_error(&mut env, format!("Failed to read cache directory: {error}"));
            return 0;
        }
    };

    match SpotifyRuntime::new(cache_dir) {
        Ok(runtime) => Box::into_raw(Box::new(runtime)) as jlong,
        Err(error) => {
            throw_bridge_error(&mut env, error.to_string());
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_metrolist_music_spotify_bridge_SpotifyBridge_nativeRelease(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }

    unsafe {
        drop(Box::from_raw(handle as *mut SpotifyRuntime));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_metrolist_music_spotify_bridge_SpotifyBridge_nativeSmokeTest(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return JNI_FALSE;
    }

    let runtime = unsafe { (handle as *mut SpotifyRuntime).as_ref() };
    match runtime {
        Some(runtime) if runtime.smoke_test() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

fn throw_bridge_error(env: &mut JNIEnv, message: String) {
    error!("{message}");
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}
