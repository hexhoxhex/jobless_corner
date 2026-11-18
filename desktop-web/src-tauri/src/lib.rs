mod capture;
mod relay;

use tauri::{Manager, RunEvent, WindowEvent};

/// Where the page sends source requests (see relay.rs).
#[tauri::command]
fn relay_info(state: tauri::State<'_, relay::RelayInfo>) -> relay::RelayInfo {
    state.inner().clone()
}

/// `--engine` (debug builds only): the backend without its window - the
/// relay and the hidden player window on 127.0.0.1:8797 (secret "dev"), for
/// working on the UI in an ordinary browser (`bun run dev`).
fn engine_mode() -> bool {
    cfg!(debug_assertions) && std::env::args().any(|a| a == "--engine")
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let engine = engine_mode();
    let mut builder = tauri::Builder::default();
    // A second launch focuses the window that is already open. Not for the
    // engine: it shares the app's id, so a running engine made the installed
    // app hand over to it and quit - no window at all (2026-11-18).
    if !engine {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.show();
                let _ = w.set_focus();
            }
        }));
    }
    builder
        // Size, position and maximised state of the main window come back as
        // they were left. Only the main one: restoring a hidden player window
        // made it visible (an empty window on screen, 2026-11-18).
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_filter(|label| label == "main")
                .build(),
        )
        .plugin(tauri_plugin_opener::init())
        .setup(move |app| {
            let info = tauri::async_runtime::block_on(relay::start(app.handle().clone(), engine));
            if engine {
                println!("engine: relay on http://127.0.0.1:{} (secret \"{}\")", info.port, info.secret);
            }
            app.manage(info);
            if !engine {
                let cfg = app.config().app.windows.first().cloned().expect("main window config");
                tauri::WebviewWindowBuilder::from_config(app.handle(), &cfg)?.build()?;
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the main window ends the app, even with a hidden player
            // window still working.
            if window.label() == "main" && matches!(event, WindowEvent::CloseRequested { .. }) {
                window.app_handle().exit(0);
            }
        })
        .invoke_handler(tauri::generate_handler![relay_info])
        .build(tauri::generate_context!())
        .expect("error while building Vijana BaruBaru")
        .run(move |_app, event| {
            // The engine has no window of its own: a hidden player window
            // closing must not end it.
            if let RunEvent::ExitRequested { api, code, .. } = event {
                if engine && code.is_none() {
                    api.prevent_exit();
                }
            }
        });
}
