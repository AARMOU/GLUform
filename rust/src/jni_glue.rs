//! JNI entry points for `com.gluform.app.engine.Native`.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jbyteArray, jdouble, jstring};

fn text(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|x| x.into()).unwrap_or_default()
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|x| x.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_execute<'a>(mut env: JNIEnv<'a>, _: JClass<'a>, cmd: JString<'a>, params: JString<'a>) -> jstring {
    let (c, p) = (text(&mut env, &cmd), text(&mut env, &params));
    let r = crate::execute(&c, &p);
    out(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_status<'a>(mut env: JNIEnv<'a>, _: JClass<'a>) -> jstring {
    let r = std::panic::catch_unwind(crate::status).unwrap_or_else(|_| "{}".into());
    out(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_scene<'a>(env: JNIEnv<'a>, _: JClass<'a>) -> jbyteArray {
    let bytes = crate::scene();
    env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_pick<'a>(
    mut env: JNIEnv<'a>,
    _: JClass<'a>,
    ox: jdouble,
    oy: jdouble,
    oz: jdouble,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    tol0: jdouble,
    tolk: jdouble,
) -> jstring {
    let r = std::panic::catch_unwind(|| crate::pick([ox, oy, oz], [dx, dy, dz], tol0, tolk)).unwrap_or_else(|_| "{}".into());
    out(&mut env, r)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_reset<'a>(_: JNIEnv<'a>, _: JClass<'a>) {
    let _ = std::panic::catch_unwind(crate::reset);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_gluform_app_engine_Native_sketchCurves<'a>(mut env: JNIEnv<'a>, _: JClass<'a>) -> jstring {
    let r = std::panic::catch_unwind(crate::sketch_curves).unwrap_or_else(|_| "[]".into());
    out(&mut env, r)
}
