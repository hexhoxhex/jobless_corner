// Prevents additional console window on Windows in release.
// Without this, double-clicking the .exe also opens a black cmd window.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    vijanabarubaru_lib::run()
}
