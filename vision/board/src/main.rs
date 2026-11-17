//! Shows Marco's calibration board on a computer screen, to calibrate a phone
//! camera against: one board pixel per screen pixel at any window size.
//!
//! On a resize the board is rendered again for the new size in the background;
//! meanwhile the old one is shown cropped, never scaled.
//!
//! ```text
//! cargo run --release -p marco-board -- [--square PX] [--windowed]
//! ```
//!
//! Keys: `+` / `-` square size, `F` or F11 fullscreen, `Q` or Esc quit.

use std::num::NonZeroU32;
use std::rc::Rc;
use std::thread;

use winit::application::ApplicationHandler;
use winit::event::{ElementState, KeyEvent, WindowEvent};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop, EventLoopProxy};
use winit::keyboard::{Key, NamedKey};
use winit::window::{Fullscreen, Window, WindowId};

/// About 9 mm on a ~110 dpi monitor: a phone ultrawide at 30–50 cm sees squares of
/// 5–8 measured pixels, read quickly (0.5 s a frame on a Pixel 7a, against 1.8 s for
/// 4 px squares seen from close up) and fitted to 0.09 px.
const DEFAULT_SQUARE_PX: u32 = 40;
const MIN_SQUARE_PX: u32 = 8;

/// A board rendered for one window size and square size, as 0RGB pixels.
struct Board {
    width: u32,
    height: u32,
    square: u32,
    pixels: Vec<u32>,
    render_ms: u128,
}

impl Board {
    fn render(width: u32, height: u32, square: u32) -> Self {
        let started = std::time::Instant::now();
        let grey = marco_vision::render_board(width as usize, height as usize, square as f64);
        let pixels = grey.into_iter().map(|g| u32::from(g) * 0x01_01_01).collect();
        Self { width, height, square, pixels, render_ms: started.elapsed().as_millis() }
    }
}

struct App {
    proxy: EventLoopProxy<Board>,
    square: u32,
    fullscreen: bool,
    window: Option<Rc<Window>>,
    surface: Option<softbuffer::Surface<Rc<Window>, Rc<Window>>>,
    board: Option<Board>,
    /// The size and square the board should have now.
    wanted: (u32, u32, u32),
    /// A render is running; another starts when it lands if `wanted` moved on.
    rendering: bool,
}

impl App {
    fn want(&mut self, width: u32, height: u32) {
        self.wanted = (width, height, self.square);
        self.render_if_needed();
    }

    fn render_if_needed(&mut self) {
        let (width, height, square) = self.wanted;
        let current = self.board.as_ref().map(|b| (b.width, b.height, b.square));
        if self.rendering || width == 0 || height == 0 || current == Some(self.wanted) {
            return;
        }
        self.rendering = true;
        let proxy = self.proxy.clone();
        thread::spawn(move || {
            let _ = proxy.send_event(Board::render(width, height, square));
        });
    }

    fn draw(&mut self) {
        let (Some(window), Some(surface)) = (&self.window, &mut self.surface) else {
            return;
        };
        let size = window.inner_size();
        let Ok(mut buffer) = surface.buffer_mut() else {
            return;
        };
        buffer.fill(0);
        if let Some(board) = &self.board {
            // Cropped to the window, pixel for pixel.
            let columns = board.width.min(size.width) as usize;
            for row in 0..board.height.min(size.height) as usize {
                let from = row * board.width as usize;
                let to = row * size.width as usize;
                buffer[to..to + columns].copy_from_slice(&board.pixels[from..from + columns]);
            }
        }
        let _ = buffer.present();
    }

    fn set_square(&mut self, square: u32) {
        self.square = square.max(MIN_SQUARE_PX);
        let (width, height, _) = self.wanted;
        self.want(width, height);
    }
}

impl ApplicationHandler<Board> for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let attributes = Window::default_attributes().with_title("Marco calibration board");
        let window = Rc::new(event_loop.create_window(attributes).expect("a window"));
        if self.fullscreen {
            window.set_fullscreen(Some(Fullscreen::Borderless(None)));
        }
        window.set_cursor_visible(false);
        let context = softbuffer::Context::new(window.clone()).expect("a software surface");
        self.surface = Some(softbuffer::Surface::new(&context, window.clone()).expect("a software surface"));
        self.window = Some(window);
    }

    fn user_event(&mut self, _event_loop: &ActiveEventLoop, board: Board) {
        self.rendering = false;
        let scale = self.window.as_ref().map_or(1.0, |window| window.scale_factor());
        println!(
            "board {}×{} px, squares {} px: {}×{} squares (rendered in {} ms, display scale {scale})",
            board.width,
            board.height,
            board.square,
            board.width / board.square,
            board.height / board.square,
            board.render_ms,
        );
        self.board = Some(board);
        if let Some(window) = &self.window {
            window.request_redraw();
        }
        self.render_if_needed();
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _id: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => {
                if let (Some(surface), Some(width), Some(height)) =
                    (&mut self.surface, NonZeroU32::new(size.width), NonZeroU32::new(size.height))
                {
                    let _ = surface.resize(width, height);
                }
                self.want(size.width, size.height);
                if let Some(window) = &self.window {
                    window.request_redraw();
                }
            }
            WindowEvent::RedrawRequested => self.draw(),
            WindowEvent::KeyboardInput {
                event: KeyEvent { logical_key, state: ElementState::Pressed, .. },
                ..
            } => match logical_key {
                Key::Named(NamedKey::Escape) => event_loop.exit(),
                Key::Named(NamedKey::F11) => self.toggle_fullscreen(),
                Key::Character(c) => match c.as_str() {
                    "q" | "Q" => event_loop.exit(),
                    "f" | "F" => self.toggle_fullscreen(),
                    "+" | "=" => self.set_square(self.square + 1),
                    "-" | "_" => self.set_square(self.square.saturating_sub(1)),
                    _ => {}
                },
                _ => {}
            },
            _ => {}
        }
    }
}

impl App {
    fn toggle_fullscreen(&mut self) {
        if let Some(window) = &self.window {
            let full = window.fullscreen().is_some();
            window.set_fullscreen(if full { None } else { Some(Fullscreen::Borderless(None)) });
        }
    }
}

fn main() {
    let mut square = DEFAULT_SQUARE_PX;
    let mut fullscreen = true;
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--square" => {
                square = args.next().and_then(|v| v.parse().ok()).unwrap_or_else(|| usage("--square needs a number of pixels"))
            }
            "--windowed" => fullscreen = false,
            "-h" | "--help" => usage(""),
            other => usage(&format!("unknown argument {other}")),
        }
    }

    let event_loop = EventLoop::<Board>::with_user_event().build().expect("an event loop");
    event_loop.set_control_flow(ControlFlow::Wait);
    let mut app = App {
        proxy: event_loop.create_proxy(),
        square: square.max(MIN_SQUARE_PX),
        fullscreen,
        window: None,
        surface: None,
        board: None,
        wanted: (0, 0, square),
        rendering: false,
    };
    event_loop.run_app(&mut app).expect("the event loop to run");
}

fn usage(problem: &str) -> ! {
    if !problem.is_empty() {
        eprintln!("{problem}");
    }
    eprintln!("usage: marco-board [--square PX] [--windowed]\nkeys: + / - square size, F or F11 fullscreen, Q or Esc quit");
    std::process::exit(if problem.is_empty() { 0 } else { 2 });
}
