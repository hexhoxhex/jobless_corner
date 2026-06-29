// Tauri entry — keeps it tiny for now. Tomorrow we can add
// #[tauri::command] handlers if we go the Rust-backend route for H5
// signing or live-TV proxy (see PLANNING.md "Option B"). For v1 the
// frontend does everything in TypeScript and Tauri just hosts the
// WebView.

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
