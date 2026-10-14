use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use cold_clear::evaluation::Standard;
use cold_clear::{Interface, Options};
use libtetris::{
    Board, CellColor, ColoredRow, FallingPiece, LockResult as TetrisLockResult,
    Piece as TetrisPiece, Row, SpawnRule,
};

// 이 매크로 하나로 이 파일 안의 #[uniffi::export]들을 스캐닝해서
// 바인딩 생성에 필요한 메타데이터를 컴파일된 .so 안에 심어줍니다.
uniffi::setup_scaffolding!();

// ── 조각(피스) 종류 ──────────────────────────────────────────────
// cold-clear 내부 Piece 타입을 그대로 노출하는 대신, uniffi가 이해할 수 있는
// 우리 타입을 하나 만들고 서로 변환합니다. (내부 타입을 직접 export하면
// cold-clear 쪽 구조가 바뀔 때마다 Kotlin 쪽까지 깨지기 쉬워서, 얇은 어댑터를 둡니다)
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum PieceKind {
    I,
    O,
    T,
    L,
    J,
    S,
    Z,
}

impl From<PieceKind> for TetrisPiece {
    fn from(p: PieceKind) -> Self {
        match p {
            PieceKind::I => TetrisPiece::I,
            PieceKind::O => TetrisPiece::O,
            PieceKind::T => TetrisPiece::T,
            PieceKind::L => TetrisPiece::L,
            PieceKind::J => TetrisPiece::J,
            PieceKind::S => TetrisPiece::S,
            PieceKind::Z => TetrisPiece::Z,
        }
    }
}

impl From<TetrisPiece> for PieceKind {
    fn from(p: TetrisPiece) -> Self {
        match p {
            TetrisPiece::I => PieceKind::I,
            TetrisPiece::O => PieceKind::O,
            TetrisPiece::T => PieceKind::T,
            TetrisPiece::L => PieceKind::L,
            TetrisPiece::J => PieceKind::J,
            TetrisPiece::S => PieceKind::S,
            TetrisPiece::Z => PieceKind::Z,
        }
    }
}

// ── 필드 한 칸의 상태 (화면에 그릴 때 색 구분용) ──────────────────
// 그냥 bool(찼다/비었다)만으로는 미노 색을 못 그리니까, 실제 조각 종류나
// 가비지인지까지 구분해서 넘겨줍니다.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CellKind {
    Empty,
    Garbage,
    I,
    O,
    T,
    L,
    J,
    S,
    Z,
}

impl From<CellColor> for CellKind {
    fn from(c: CellColor) -> Self {
        match c {
            CellColor::Empty => CellKind::Empty,
            CellColor::Garbage | CellColor::Unclearable => CellKind::Garbage,
            CellColor::I => CellKind::I,
            CellColor::O => CellKind::O,
            CellColor::T => CellKind::T,
            CellColor::L => CellKind::L,
            CellColor::J => CellKind::J,
            CellColor::S => CellKind::S,
            CellColor::Z => CellKind::Z,
        }
    }
}

impl From<TetrisPiece> for CellKind {
    fn from(p: TetrisPiece) -> Self {
        match p {
            TetrisPiece::I => CellKind::I,
            TetrisPiece::O => CellKind::O,
            TetrisPiece::T => CellKind::T,
            TetrisPiece::L => CellKind::L,
            TetrisPiece::J => CellKind::J,
            TetrisPiece::S => CellKind::S,
            TetrisPiece::Z => CellKind::Z,
        }
    }
}

// ── 한 번의 배치 결과를 Kotlin에 넘겨줄 형태 ──────────────────────
#[derive(uniffi::Record, Clone, Debug)]
pub struct LockInfo {
    pub lines_cleared: u32,
    pub garbage_sent: u32,
    pub is_b2b: bool,
    pub combo: Option<u32>,
    pub perfect_clear: bool,
    pub topped_out: bool,
    // true면 이번 턴에 AI가 제한 시간 안에 응답을 안 줘서 그냥 건너뛴 것.
    pub stalled: bool,
}

// libtetris 기본 콤보 테이블 대신 우리만의 테이블로 콤보 보너스를 계산
// (0~1콤보 +0, 2~3 +1, 4~5 +2, 6~7 +3, 8~12 +4, 13+ +5).
// 베이스 공격력(클리어 종류)과 B2B 보너스는 libtetris 계산을 그대로 쓰고,
// 퍼펙트클리어는 libtetris처럼 무조건 10으로 유지합니다.
const CUSTOM_COMBO_GARBAGE: [u32; 14] = [
    0, 0, // 0, 1 콤보
    1, 1, // 2, 3 콤보
    2, 2, // 4, 5 콤보
    3, 3, // 6, 7 콤보
    4, 4, 4, 4, 4, // 8, 9, 10, 11, 12 콤보
    5, // 13+ 콤보
];

fn compute_attack(r: &TetrisLockResult) -> u32 {
    if r.perfect_clear {
        return 10;
    }
    let base = r.placement_kind.garbage();
    let b2b_bonus = if r.b2b { 1 } else { 0 };
    let combo_bonus = match r.combo {
        Some(c) => {
            let idx = c as usize;
            if idx >= CUSTOM_COMBO_GARBAGE.len() {
                *CUSTOM_COMBO_GARBAGE.last().unwrap()
            } else {
                CUSTOM_COMBO_GARBAGE[idx]
            }
        }
        None => 0,
    };
    base + b2b_bonus + combo_bonus
}

impl From<TetrisLockResult> for LockInfo {
    fn from(r: TetrisLockResult) -> Self {
        let garbage_sent = compute_attack(&r);
        LockInfo {
            lines_cleared: r.cleared_lines.len() as u32,
            garbage_sent,
            is_b2b: r.b2b,
            combo: r.combo,
            perfect_clear: r.perfect_clear,
            topped_out: r.locked_out,
            stalled: false,
        }
    }
}

// ── 가비지 큐 한 칸 (화면 표시용) ─────────────────────────────────
#[derive(uniffi::Record, Clone, Debug)]
pub struct GarbageEntry {
    pub lines: u32,
    // true(빨강) = 다음 내 턴 시작할 때 바로 필드에 얹힘
    // false(주황) = 아직 대기 중, 내가 한 번 더 두고 나면 활성화됨
    pub active: bool,
}

// 내부 전용. delay_remaining이 0이 되면 활성화(빨강)된 걸로 취급합니다.
#[derive(Clone, Debug)]
struct GarbageChunk {
    lines: u32,
    delay_remaining: u32,
}

/// 큐 맨 앞부터 순서대로 amount만큼 깎는다 (활성화 여부 상관없이 상쇄
/// 가능하게, front부터). 다 깎고도 공격력이 남으면 그 나머지를 반환한다
/// (그 나머지만 상대에게 보내면 됨).
fn cancel_garbage(queue: &mut VecDeque<GarbageChunk>, mut amount: u32) -> u32 {
    while amount > 0 {
        if let Some(front) = queue.front_mut() {
            if front.lines <= amount {
                amount -= front.lines;
                queue.pop_front();
            } else {
                front.lines -= amount;
                amount = 0;
            }
        } else {
            break;
        }
    }
    amount
}

/// 이번 턴에 낸 공격(outgoing_attack)을 먼저 내 큐(앞에서부터, 활성화
/// 여부 상관없이)로 상쇄하고, 그러고도 도착 예정(delay==0)인 게 남아있으면
/// 그 줄 수만큼만 반환합니다(호출한 쪽에서 필드에 얹으면 됨). 아직 대기
/// 중인(delay>0) 건 이번 턴이 지났으니 delay를 1 깎아둡니다.
///
/// 반환값: (이번에 실제로 필드에 얹어야 할 줄 수, 상쇄하고 남아서 상대에게
/// 보내야 할 공격력)
fn resolve_turn_garbage(queue: &mut VecDeque<GarbageChunk>, outgoing_attack: u32) -> (u32, u32) {
    let leftover_attack = cancel_garbage(queue, outgoing_attack);

    let mut applied = 0u32;
    while let Some(front) = queue.front() {
        if front.delay_remaining == 0 {
            applied += front.lines;
            queue.pop_front();
        } else {
            break;
        }
    }
    for chunk in queue.iter_mut() {
        chunk.delay_remaining = chunk.delay_remaining.saturating_sub(1);
    }

    (applied, leftover_attack)
}

/// 큐 맨 앞부터 delay_remaining==0(다음 턴에 바로 얹힐 예정)인 것들의
/// 합계만 반환합니다. 실제로 큐를 건드리지 않는 "미리보기"용입니다.
/// 봇한테 suggest_next_move(incoming)으로 넘겨줘서 위험을 감안한 판단을
/// 하게 만드는 데 씁니다.
/// 큐에 쌓인 가비지 전체(활성화된 것 + 아직 대기 중인 것 전부)의 합계를
/// 반환합니다. 실제로 큐를 건드리지 않는 "미리보기"용입니다. 봇한테
/// suggest_next_move(incoming)으로 넘겨줘서 "곧 이만큼 받을 예정"이라는
/// 걸 감안한(대기 기간 동안 미리 대비하는) 판단을 하게 만드는 데 씁니다.
fn total_pending_garbage(queue: &VecDeque<GarbageChunk>) -> u32 {
    queue.iter().map(|c| c.lines).sum()
}
// 화면에 그릴 필드 한 칸씩을 위에서부터가 아니라 "아래서부터" 담아서 넘겨줍니다.
// (libtetris 내부 좌표계가 y=0이 맨 아래라서, 그대로 맞춰둡니다. 화면에 그릴 때
// Kotlin 쪽에서 위아래를 뒤집어서 그리면 됩니다)
// 표준은 20줄이지만, 조각이 스폰될 때 20번째 줄보다 살짝 위(21번째 줄)에
// 걸치는 경우가 있어서 그것까지는 보이게 2줄 여유를 둡니다.
const VISIBLE_ROWS: usize = 22;

// 실제 미노 색깔을 알려면 ColoredRow를 쓰는 보드가 필요합니다 (봇에 넘기는
// 기본 보드는 u16이라 "찼다/안 찼다"만 알 수 있고 무슨 조각인지는 모릅니다).
fn board_field_grid(board: &Board<ColoredRow>) -> [[CellKind; 10]; VISIBLE_ROWS] {
    let mut grid = [[CellKind::Empty; 10]; VISIBLE_ROWS];
    for y in 0..VISIBLE_ROWS {
        let row = board.get_row(y as i32);
        for x in 0..10 {
            grid[y][x] = row.cell_color(x).into();
        }
    }
    grid
}

fn flatten(grid: [[CellKind; 10]; VISIBLE_ROWS]) -> Vec<CellKind> {
    let mut flat = Vec::with_capacity(VISIBLE_ROWS * 10);
    for y in 0..VISIBLE_ROWS {
        for x in 0..10 {
            flat.push(grid[y][x]);
        }
    }
    flat
}

/// 한 번에 여러 줄을 얹을 때 각 줄의 구멍 칸을 정합니다. 첫 줄은 완전
/// 랜덤, 그 다음 줄부터는 same_column_chance 확률로 바로 앞 줄과 같은 칸
/// (세로로 뚫려서 한 번에 처리하기 쉽게), 그 나머지 확률로 다른 9칸 중
/// 랜덤입니다. (0.0 ~ 1.0)
fn garbage_columns(count: u32, same_column_chance: f64) -> Vec<usize> {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let mut cols = Vec::with_capacity(count as usize);
    let mut prev: Option<usize> = None;

    for _ in 0..count {
        let col = match prev {
            None => rng.gen_range(0..10),
            Some(p) => {
                if rng.gen_bool(same_column_chance.clamp(0.0, 1.0)) {
                    p
                } else {
                    // 이전 칸을 제외한 나머지 9칸 중 랜덤
                    let mut c = rng.gen_range(0..9);
                    if c >= p {
                        c += 1;
                    }
                    c
                }
            }
        };
        cols.push(col);
        prev = Some(col);
    }

    cols
}

/// 7-bag 방식으로 다음 조각을 하나 뽑는다. 가방이 비면 7종류를 다시 섞어서 채운다.
fn draw_from_bag(bag_queue: &mut Vec<TetrisPiece>) -> TetrisPiece {
    use rand::seq::SliceRandom;
    if bag_queue.is_empty() {
        *bag_queue = vec![
            TetrisPiece::I, TetrisPiece::O, TetrisPiece::T, TetrisPiece::L,
            TetrisPiece::J, TetrisPiece::S, TetrisPiece::Z,
        ];
        bag_queue.shuffle(&mut rand::thread_rng());
    }
    bag_queue.pop().unwrap()
}

/// 조각 하나를 "뽑습니다" — 그런데 예전에 이 지점(draw_index)까지 이미
/// 한 번 생성된 적이 있으면(언도 후 다시 그 지점을 지나가는 중이면) 그때
/// 기록해둔 조각을 그대로 재사용하고, 처음 와보는 지점이면 새로 뽑아서
/// 기록에 남깁니다. 이러면 "언도 → 다시 두기"를 해도 전에 봤던 미래의
/// 조각 순서가 그대로 재현되고, 기록보다 더 멀리 나아갈 때만 새로운
/// 랜덤이 섞입니다.
///
/// `piece_sequence`/`bag_queue`는 일부러 스냅샷(undo) 대상이 아닙니다 —
/// 여러 시점(타임라인)이 같은 하나의 "지금까지 생성된 전체 기록"을
/// 공유해야 하기 때문입니다. 대신 `draw_index`(지금 이 시점이 그 기록의
/// 몇 번째까지 왔는지)만 스냅샷에 포함되어 언도 때 같이 되돌아갑니다.
fn draw_piece(inner: &mut MatchInner) -> TetrisPiece {
    let piece = if inner.draw_index < inner.piece_sequence.len() {
        inner.piece_sequence[inner.draw_index]
    } else {
        let p = draw_from_bag(&mut inner.bag_queue);
        inner.piece_sequence.push(p);
        p
    };
    inner.draw_index += 1;
    piece
}

/// 두 필드 + (AI의 색상용 미러 보드) 다음 조각 큐가 항상 최소 이만큼은
/// 채워져 있도록 보충한다. 플레이어/AI가 항상 같은 순서로 조각을 받아야
/// 공평하므로, 여기서 하나 뽑아서 세 보드 모두에 동시에 넣어줍니다.
const MIN_QUEUE_LEN: usize = 8;

fn ensure_queue(inner: &mut MatchInner) {
    // AI가 홀드를 쓰면 한 턴에 큐를 2개까지 소비할 수 있어서 ai_board 쪽만
    // 몰래 바닥날 수 있었던 버그가 있었어서, 두 필드 다 확인해서 보충합니다.
    while inner.player_board.next_queue().count() < MIN_QUEUE_LEN
        || inner.ai_board.next_queue().count() < MIN_QUEUE_LEN
    {
        let piece = draw_piece(inner);
        inner.player_board.add_next_piece(piece);
        inner.ai_board.add_next_piece(piece);
        inner.ai_board_colored.add_next_piece(piece);
    }
}

// ── 게임 한 판 ──────────────────────────────────────────────────
// uniffi::Object로 표시하면 Kotlin에서는 Arc로 참조되는 "핸들" 객체가 됩니다.
// 메서드가 &self만 받아도(코틀린에서 여러 곳에서 참조를 들고 있을 수 있으니)
// 내부 상태 변경은 Mutex로 감싸서 처리합니다.
#[derive(uniffi::Object)]
pub struct Match {
    inner: Mutex<MatchInner>,
}

// ── 게임 종료 결과 화면용 ──────────────────────────────────────────
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum GameOverSide {
    Player,
    Ai,
}

#[derive(uniffi::Record, Clone, Debug, Default)]
pub struct MatchStats {
    pub player_pieces: u32,
    // 상쇄 여부와 상관없이, 플레이어가 낸 공격력 총합.
    pub player_total_attack: u32,
    // 그중 실제로 AI 가비지 큐에 들어간(상쇄되지 않은) 만큼.
    pub player_lines_sent: u32,
    // 플레이어가 자기 공격으로 자기 큐를 상쇄시킨 양.
    pub player_garbage_cancelled: u32,
    // 상쇄되지 않고 실제로 플레이어 필드에 생성된 방해줄.
    pub player_garbage_created: u32,
    // 플레이어가 라인클리어로 없앤 방해줄.
    pub player_garbage_cleared: u32,
    // AI가 낸 공격력 총합 (상쇄 여부 상관없이).
    pub ai_total_attack: u32,
    // 그중 실제로 플레이어 가비지 큐에 들어간(상쇄되지 않은) 만큼.
    pub ai_lines_sent: u32,
}

// ── 언도용 스냅샷 ─────────────────────────────────────────────
// 매 라운드(플레이어 하드드롭) 시작 직전의 전체 상태를 통째로 복제해서
// 보관합니다. Board/FallingPiece 등이 전부 Clone 가능해서 그대로 복제만
// 하면 되고, 되돌릴 때도 이 값을 그대로 다시 덮어쓰기만 하면 됩니다.
#[derive(uniffi::Record, Clone, Debug)]
pub struct HintStep {
    // 몇 수 앞인지 (0 = 지금 당장 놓을 조각).
    pub depth: u32,
    pub cells: Vec<CellKind>,
}

#[derive(Clone)]
struct Snapshot {
    player_board: Board<ColoredRow>,
    ai_board: Board,
    ai_board_colored: Board<ColoredRow>,
    player_garbage_queue: VecDeque<GarbageChunk>,
    ai_garbage_queue: VecDeque<GarbageChunk>,
    // bag_queue/piece_sequence는 일부러 여기 안 들어있습니다 — 모든
    // 타임라인이 공유하는 "지금까지 생성된 조각 전체 기록"이라 스냅샷마다
    // 따로 들고 있으면 안 됩니다. 이 스냅샷이 그 기록의 몇 번째 지점이었는지만
    // draw_index로 기억해둡니다.
    draw_index: usize,
    player_falling: Option<FallingPiece>,
    game_over: bool,
    topped_out_side: Option<GameOverSide>,
    stats: MatchStats,
}

// 너무 많이 쌓이면 메모리를 낭비하니 최대 개수를 제한합니다.
// (필드 하나가 커봐야 수 KB 수준이라 20개는 사실 메모리상 부담이 거의
// 없는 수준이지만, 무한정 되돌릴 수 있게 하는 것도 게임 성격상 이상해서
// UX 관점에서 상한을 둡니다)
fn take_snapshot(inner: &MatchInner, keep_falling: bool) -> Snapshot {
    // 조작 중이던 조각은 "같은 종류를 지금 이 보드 기준으로 다시 스폰"한
    // 것으로 저장합니다. 조각 큐에서 다시 꺼내오는 방식이 아니라서, 이번
    // 라운드에 실제로 쓰던 조각 종류가 큐 소비 여부와 상관없이 정확히
    // 보존됩니다. (조작 전이라 player_falling이 None이면 그대로 None —
    // 이 경우엔 나중에 큐에서 새로 스폰해도 문제 없습니다)
    //
    // keep_falling이 true면(AI 단독 턴) 플레이어가 조작하던 조각의 위치/회전을
    // 스폰 상태로 리셋하지 않고 그대로 저장합니다 — 이 경우엔 플레이어의 수가
    // 진행된 게 아니라서, 언도해도 지금 움직이던 그대로 돌아와야 자연스럽습니다.
    let reset_falling = if keep_falling {
        inner.player_falling
    } else {
        inner
            .player_falling
            .and_then(|p| SpawnRule::Row19Or20.spawn(p.kind.0, &inner.player_board))
    };

    Snapshot {
        player_board: inner.player_board.clone(),
        ai_board: inner.ai_board.clone(),
        ai_board_colored: inner.ai_board_colored.clone(),
        player_garbage_queue: inner.player_garbage_queue.clone(),
        ai_garbage_queue: inner.ai_garbage_queue.clone(),
        draw_index: inner.draw_index,
        player_falling: reset_falling,
        game_over: inner.game_over,
        topped_out_side: inner.topped_out_side,
        stats: inner.stats.clone(),
    }
}

fn restore_snapshot(inner: &mut MatchInner, snap: Snapshot) {
    inner.player_board = snap.player_board;
    inner.ai_board = snap.ai_board;
    inner.ai_board_colored = snap.ai_board_colored;
    inner.player_garbage_queue = snap.player_garbage_queue;
    inner.ai_garbage_queue = snap.ai_garbage_queue;
    inner.draw_index = snap.draw_index;
    inner.player_falling = snap.player_falling;
    inner.game_over = snap.game_over;
    inner.topped_out_side = snap.topped_out_side;
    inner.stats = snap.stats;
}

/// 플레이어 하드드롭 직전용 — 조작 중이던 조각은 스폰 상태로 리셋해서 저장.
fn push_history(inner: &mut MatchInner) {
    let snap = take_snapshot(inner, false);
    inner.history.push_back(snap);
    // 더 이상 개수 제한을 두지 않습니다 — 게임 시작부터 전부 되돌릴 수 있습니다.
}

/// AI 단독 턴 직전용 — 플레이어가 조작 중이던 조각 위치를 그대로 보존해서 저장.
fn push_history_keep_piece(inner: &mut MatchInner) {
    let snap = take_snapshot(inner, true);
    inner.history.push_back(snap);
}

struct MatchInner {
    // 플레이어 필드는 봇한테 넘길 일이 없어서 처음부터 색상 보드로 둡니다.
    player_board: Board<ColoredRow>,
    // AI 필드는 봇 라이브러리가 요구하는 타입(u16, 색상 정보 없음)이라
    // 실제 게임 로직/봇 연동용으로 이거 하나만 씁니다.
    ai_board: Board,
    // 위 ai_board랑 완전히 동일한 순서로 계속 미러링만 하는 색상용 보드.
    // 화면에 AI 필드를 그릴 때(색 표시)만 씁니다.
    ai_board_colored: Board<ColoredRow>,
    // 상대가 나에게 보낸 가비지 큐. 각 항목은 몇 줄인지 + 아직 몇 턴 더
    // 대기해야 활성화되는지를 들고 있습니다.
    player_garbage_queue: VecDeque<GarbageChunk>,
    ai_garbage_queue: VecDeque<GarbageChunk>,
    // 가비지가 도착한 후 활성화(실제로 필드에 얹힘)까지 몇 턴 대기하는지.
    // 0이면 예전처럼 바로 다음 턴에 즉시 적용됩니다.
    garbage_delay: u32,
    // 여러 줄이 한 번에 얹힐 때, 이전 줄과 같은 칸에 구멍이 뚫릴 확률(0.0~1.0).
    garbage_same_column_chance: f64,
    // AI 탐색 설정 (설정 화면에서 조절 가능).
    ai_min_nodes: u32,
    ai_max_nodes: u32,
    ai_threads: u32,
    ai_speculate: bool,
    // 7-bag 조각 생성용. 비어있으면 새로 7개 섞어서 채웁니다.
    // ※ 이 필드는 언도(undo)로 되돌아가지 않습니다 — piece_sequence와
    // 묶여서 "지금까지 생성된 조각들의 가방 상태"를 나타내는 공유 자원이라,
    // 어느 타임라인에서 새로 뽑든 7개 단위 규칙이 끊기지 않게 유지됩니다.
    bag_queue: Vec<TetrisPiece>,
    // 지금까지 한 번이라도 생성된 적 있는 조각 전체 기록. 언도로 되돌아가지
    // 않습니다 — "언도 후 다시 두기"를 할 때 예전에 봤던 미래의 조각
    // 순서를 그대로 재현하기 위한 기록입니다.
    piece_sequence: Vec<TetrisPiece>,
    // 지금 이 시점이 piece_sequence의 몇 번째까지 왔는지. 이건 언도하면
    // 같이 되돌아갑니다 (Snapshot에 포함됨).
    draw_index: usize,
    // 지금 플레이어가 조작 중인 조각. None이면 "아직 안 나옴" 상태.
    player_falling: Option<FallingPiece>,
    game_over: bool,
    // 어느 쪽이 탑아웃돼서 게임이 끝났는지 (결과 화면 승/패 판정용).
    topped_out_side: Option<GameOverSide>,
    // 이번 판 누적 통계 (결과 화면용).
    stats: MatchStats,
    // 언도용 히스토리 스택. 맨 뒤(back)가 가장 최근 라운드 시작 시점.
    history: VecDeque<Snapshot>,
}

fn bot_options(min_nodes: u32, max_nodes: u32, threads: u32, speculate: bool) -> Options {
    Options {
        // min_nodes=0(기본값)이면 "최소 탐색량" 기준이 이미 충족된 상태라서,
        // 봇이 처음 찾은 아무 후보나 거의 바로 답으로 내놔도 됐습니다.
        min_nodes,
        max_nodes,
        threads,
        speculate,
        ..Options::default()
    }
}

/// 플레이어 쪽에 현재 조작 중인 조각이 없으면 새로 스폰한다.
/// (턴 시작 시점이므로, 밀린 가비지도 여기서 필드에 반영한다)
fn ensure_player_piece(inner: &mut MatchInner) {
    if inner.game_over || inner.player_falling.is_some() {
        return;
    }

    // (가비지는 더 이상 여기서 얹지 않습니다 — 이번 턴의 내 공격으로 상쇄할
    // 기회를 준 다음, player_hard_drop 안에서 락 직후에 반영합니다)

    ensure_queue(inner);
    let next = inner.player_board.get_next_piece().unwrap();
    inner.player_board.advance_queue();
    inner.player_falling = SpawnRule::Row19Or20.spawn(next, &inner.player_board);
    if inner.player_falling.is_none() {
        // 스폰할 자리조차 막혀 있음 = 탑아웃
        inner.game_over = true;
        inner.topped_out_side = Some(GameOverSide::Player);
    }
}

fn game_over_result() -> LockInfo {
    LockInfo {
        lines_cleared: 0,
        garbage_sent: 0,
        is_b2b: false,
        combo: None,
        perfect_clear: false,
        topped_out: true,
        stalled: false,
    }
}

fn stalled_result() -> LockInfo {
    LockInfo {
        lines_cleared: 0,
        garbage_sent: 0,
        is_b2b: false,
        combo: None,
        perfect_clear: false,
        topped_out: false,
        stalled: true,
    }
}

/// 힌트 계산용: 플레이어의 색상 보드(Board<ColoredRow>)를 봇이 요구하는
/// 형태(Board<u16>)로 그대로 옮겨 담습니다. 색 정보만 빠지고 필드 모양/
/// 콤보/B2B/홀드/다음 큐/가방 상태는 전부 동일하게 복제됩니다.
///
/// `current_piece`: 지금 조작 중인 조각의 종류. player_board의 next_queue()는
/// 이미 이 조각이 스폰되면서 빠져나간 뒤라 여기 포함이 안 돼있어서, 큐 맨
/// 앞에 직접 끼워 넣어야 봇이 "지금 이 조각을 어디 둘지"를 계산해줍니다
/// (안 그러면 한 칸 밀려서 그 다음 조각 기준으로 계산해버립니다).
fn plain_board_from_colored(colored: &Board<ColoredRow>, current_piece: Option<TetrisPiece>) -> Board {
    let mut plain = Board::new();
    plain.set_field(colored.get_field());
    plain.combo = colored.combo;
    plain.b2b_bonus = colored.b2b_bonus;
    plain.hold_piece = colored.hold_piece;
    if let Some(p) = current_piece {
        plain.add_next_piece(p);
    }
    for piece in colored.next_queue() {
        plain.add_next_piece(piece);
    }
    plain.bag = colored.bag;
    plain
}

/// block_next_move()는 봇이 응답할 때까지 무한정 기다리는데, 그러면 진짜 원인이
/// 뭐든 간에 앱이 영영 멈춰버립니다. 최대 8초까지만 폴링하고 포기하도록 감싸줍니다.
fn wait_for_move(bot: &Interface) -> Option<(libtetris::Move, cold_clear::Info)> {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(8);
    loop {
        match bot.poll_next_move() {
            Ok(result) => return Some(result),
            Err(cold_clear::BotPollState::Dead) => return None,
            Err(cold_clear::BotPollState::Waiting) => {
                if std::time::Instant::now() >= deadline {
                    return None;
                }
                std::thread::sleep(std::time::Duration::from_millis(15));
            }
        }
    }
}

#[uniffi::export]
impl Match {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        let player_board: Board<ColoredRow> = Board::new();
        let ai_board: Board = Board::new();
        let ai_board_colored: Board<ColoredRow> = Board::new();

        let mut inner = MatchInner {
            player_board,
            ai_board,
            ai_board_colored,
            player_garbage_queue: VecDeque::new(),
            ai_garbage_queue: VecDeque::new(),
            garbage_delay: 1, // 기본값: 한 턴 대기 후 활성화 (0으로 바꾸면 예전처럼 즉시 적용)
            garbage_same_column_chance: 0.7,
            ai_min_nodes: 100_000,
            ai_max_nodes: 2_000_000,
            ai_threads: 4,
            ai_speculate: true,
            bag_queue: Vec::new(),
            piece_sequence: Vec::new(),
            draw_index: 0,
            player_falling: None,
            game_over: false,
            topped_out_side: None,
            stats: MatchStats::default(),
            history: VecDeque::new(),
        };
        ensure_queue(&mut inner);

        Arc::new(Match {
            inner: Mutex::new(inner),
        })
    }

    pub fn player_move_left(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }
        let MatchInner { player_falling, player_board, .. } = &mut *inner;
        player_falling.as_mut().unwrap().shift(player_board, -1, 0)
    }

    pub fn player_move_right(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }
        let MatchInner { player_falling, player_board, .. } = &mut *inner;
        player_falling.as_mut().unwrap().shift(player_board, 1, 0)
    }

    pub fn player_soft_drop(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }
        let MatchInner { player_falling, player_board, .. } = &mut *inner;
        player_falling.as_mut().unwrap().shift(player_board, 0, -1)
    }

    pub fn player_rotate_cw(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }
        let MatchInner { player_falling, player_board, .. } = &mut *inner;
        player_falling.as_mut().unwrap().cw(player_board)
    }

    pub fn player_rotate_ccw(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }
        let MatchInner { player_falling, player_board, .. } = &mut *inner;
        player_falling.as_mut().unwrap().ccw(player_board)
    }

    /// 홀드. 홀드칸이 비어있었으면 큐에서 새로 하나 꺼내오고,
    /// 이미 뭔가 들어있었으면 그거랑 맞바꿔서 그걸 스폰시킵니다.
    pub fn player_hold(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        if inner.game_over {
            return false;
        }

        let current = inner.player_falling.take().unwrap().kind.0;
        let swapped = inner.player_board.hold(current);
        let next = match swapped {
            Some(p) => p,
            None => {
                ensure_queue(&mut inner);
                let p = inner.player_board.get_next_piece().unwrap();
                inner.player_board.advance_queue();
                p
            }
        };

        inner.player_falling = SpawnRule::Row19Or20.spawn(next, &inner.player_board);
        if inner.player_falling.is_none() {
            inner.game_over = true;
            inner.topped_out_side = Some(GameOverSide::Player);
        }
        true
    }

    /// 지금 조작 중인 조각을 그대로 확정(락)합니다.
    pub fn player_hard_drop(&self) -> LockInfo {
        let mut inner = self.inner.lock().unwrap();
        // 이 라운드 시작 시점 스냅샷. (조작 중인 조각은 take_snapshot에서
        // 항상 None으로 강제 저장되니, 타이밍 신경 안 써도 됩니다)
        push_history(&mut inner);

        ensure_player_piece(&mut inner);
        if inner.game_over {
            return game_over_result();
        }

        // 이번 락으로 어떤 줄이 지워질지 몰라서, 락 전에 전체 행의 색상을
        // 미리 복사해둡니다 (락 후엔 지워진 줄 내용을 볼 수 없어서).
        // 나중에 "지워진 줄 중 가비지가 섞여있던 줄" 개수를 세는 데 씁니다.
        let pre_lock_rows: Vec<[CellColor; 10]> = (0..40)
            .map(|y| {
                let row = inner.player_board.get_row(y);
                let mut arr = [CellColor::Empty; 10];
                for (x, cell) in arr.iter_mut().enumerate() {
                    *cell = row.cell_color(x);
                }
                arr
            })
            .collect();

        let mut piece = inner.player_falling.take().unwrap();
        piece.sonic_drop(&inner.player_board);
        let result = inner.player_board.lock_piece(piece);
        if result.locked_out {
            inner.game_over = true;
            inner.topped_out_side = Some(GameOverSide::Player);
        }

        inner.stats.player_pieces += 1;

        let garbage_lines_cleared = result
            .cleared_lines
            .iter()
            .filter(|&&y| {
                y >= 0
                    && (y as usize) < pre_lock_rows.len()
                    && pre_lock_rows[y as usize]
                        .iter()
                        .any(|&c| c == CellColor::Garbage)
            })
            .count() as u32;
        inner.stats.player_garbage_cleared += garbage_lines_cleared;

        let attack = compute_attack(&result);
        inner.stats.player_total_attack += attack;
        if attack > 0 || !inner.player_garbage_queue.is_empty() {
            let (applied, leftover) = resolve_turn_garbage(&mut inner.player_garbage_queue, attack);
            let cancelled = attack.saturating_sub(leftover);
            inner.stats.player_garbage_cancelled += cancelled;
            inner.stats.player_garbage_created += applied;
            inner.stats.player_lines_sent += leftover;

            if applied > 0 {
                for col in garbage_columns(applied, inner.garbage_same_column_chance) {
                    inner.player_board.add_garbage(col);
                }
            }
            if leftover > 0 {
                let delay = inner.garbage_delay;
                inner.ai_garbage_queue.push_back(GarbageChunk {
                    lines: leftover,
                    delay_remaining: delay,
                });
            }
        }
        result.into()
    }

    /// AI 턴. cargo-ndk로 빌드된 안드로이드에서는 반드시
    /// 백그라운드 스레드/코루틴에서 호출해야 UI가 멈추지 않습니다.
    ///
    /// 매번 봇을 새로 띄웁니다 (기존 봇을 계속 재사용하면서 reset()으로
    /// 필드 변경을 알려주는 방식이 원인 모를 스레드 정지를 일으켜서, 아예
    /// "상태를 오래 들고 있지 않는" 구조로 바꿨습니다).
    pub fn ai_take_turn(&self) -> LockInfo {
        let mut inner = self.inner.lock().unwrap();
        if inner.game_over {
            return game_over_result();
        }
        ensure_queue(&mut inner);

        // 봇한테 물어보기도 전에, 이번에 스폰할 조각이 애초에 놓일 자리가
        // 있는지부터 확인합니다. 자리가 없으면(탑아웃) 봇은 둘 곳을 못
        // 찾아서 응답 자체를 못 주고 "그냥 멈춘 것"처럼 보이게 되므로,
        // 여기서 먼저 걸러서 확실하게 게임 종료 처리합니다.
        let next_piece = inner.ai_board.get_next_piece().unwrap();
        if SpawnRule::Row19Or20.spawn(next_piece, &inner.ai_board).is_none() {
            inner.game_over = true;
            inner.topped_out_side = Some(GameOverSide::Ai);
            return game_over_result();
        }

        // (가비지는 더 이상 여기서 얹지 않습니다 — 이번 턴 AI의 공격으로
        // 상쇄할 기회를 준 다음, 락 직후에 반영합니다)

        // 이 턴 전용 봇을 새로 띄운다. ai_board.clone()에 현재 큐/홀드/필드가
        // 전부 담겨 있으므로 launch 시점에 이미 필요한 정보를 다 알고 시작한다.
        let options = bot_options(
            inner.ai_min_nodes,
            inner.ai_max_nodes,
            inner.ai_threads,
            inner.ai_speculate,
        );
        let bot = Interface::launch(inner.ai_board.clone(), options, Standard::default(), None);
        // 곧 필드에 얹힐 예정(활성화된)인 가비지 양을 봇한테 알려줘서,
        // 그걸 감안한 위험 회피 판단을 하게 합니다. (예전엔 항상 0을 넘겨서
        // AI가 자기 가비지 큐를 전혀 신경 안 쓰고 있었습니다)
        let incoming = total_pending_garbage(&inner.ai_garbage_queue);
        bot.suggest_next_move(incoming);
        let (hold, expected_location) = match wait_for_move(&bot) {
            Some((mv, _info)) => (mv.hold, mv.expected_location),
            None => {
                // 봇이 8초 안에 응답을 못 줬거나 죽음. "둘 곳이 없어서
                // 생각을 못 하는" 상황일 수도 있으니, 여기서 그냥 넘어가면
                // 게임이 영영 멈춘 것처럼 보입니다. 대신 최소한 "그냥 똑바로
                // 떨어뜨리기"라도 대신 둬서 진행을 이어갑니다 — 정말로 둘
                // 곳이 없다면 바로 아래 lock_piece에서 locked_out으로 잡혀
                // 정상적으로 탑아웃 처리됩니다.
                let mut fallback = SpawnRule::Row19Or20
                    .spawn(next_piece, &inner.ai_board)
                    .expect("스폰 가능 여부는 이미 위에서 확인함");
                fallback.sonic_drop(&inner.ai_board);
                (false, fallback)
            }
        };
        drop(bot); // 이 턴에만 쓰고 바로 정리 (스레드도 곧 자연 종료됨)

        // 봇이 이번에 홀드를 썼는지 여부에 따라 큐 소비를 다르게 반영해야
        // 다음 조각 큐가 실제 배치와 어긋나지 않습니다. 색상용 미러 보드에도
        // 매 단계 동일하게 적용해서 두 보드가 항상 같은 상태를 유지하게 합니다.
        if hold {
            let stashed = inner.ai_board.get_next_piece().unwrap();
            inner.ai_board.advance_queue();
            inner.ai_board_colored.advance_queue();

            let swapped = inner.ai_board.hold(stashed);
            inner.ai_board_colored.hold(stashed);

            if swapped.is_none() {
                // 홀드칸이 원래 비어있었으면, 실제로 놓일 조각은 큐에서 하나 더 나옴
                inner.ai_board.advance_queue();
                inner.ai_board_colored.advance_queue();
            }
        } else {
            inner.ai_board.advance_queue();
            inner.ai_board_colored.advance_queue();
        }

        // expected_location이 이미 바닥에 붙은 위치라고 가정하지 않고,
        // 안전하게 한 번 더 확실히 떨어뜨립니다 (이미 바닥이면 아무 영향 없음).
        let mut placement = expected_location;
        placement.sonic_drop(&inner.ai_board);

        let result = inner.ai_board.lock_piece(placement);
        inner.ai_board_colored.lock_piece(placement); // 색상 미러에도 동일 반영
        if result.locked_out {
            inner.game_over = true;
            inner.topped_out_side = Some(GameOverSide::Ai);
        }

        let attack = compute_attack(&result);
        inner.stats.ai_total_attack += attack;
        if attack > 0 || !inner.ai_garbage_queue.is_empty() {
            let (applied, leftover) = resolve_turn_garbage(&mut inner.ai_garbage_queue, attack);
            inner.stats.ai_lines_sent += leftover;
            if applied > 0 {
                for col in garbage_columns(applied, inner.garbage_same_column_chance) {
                    inner.ai_board.add_garbage(col);
                    inner.ai_board_colored.add_garbage(col);
                }
            }
            if leftover > 0 {
                let delay = inner.garbage_delay;
                inner.player_garbage_queue.push_back(GarbageChunk {
                    lines: leftover,
                    delay_remaining: delay,
                });
            }
        }
        result.into()
    }

    /// 플레이어는 두지 않고 AI만 한 수 두게 합니다. 언도로 되돌릴 수 있도록
    /// AI가 두기 직전 상태를 먼저 스냅샷으로 남깁니다 (언도 한 번이면 이
    /// AI 수만 취소됨).
    ///
    /// 일반 라운드(플레이어 드롭 뒤 이어지는 AI 응수)는 이미 드롭 시작 시점에
    /// 스냅샷이 찍혀 있으니 `ai_take_turn`을 그대로 쓰고, 이 함수는 AI만
    /// 단독으로 진행할 때 전용입니다 (이중 스냅샷 방지).
    pub fn ai_only_turn(&self) -> LockInfo {
        {
            let mut inner = self.inner.lock().unwrap();
            if inner.game_over {
                return game_over_result();
            }
            push_history_keep_piece(&mut inner);

            // 플레이어는 이번에 두지 않았지만 턴 한 번이 흘렀으니, 플레이어 쪽
            // 가비지 큐의 딜레이만 1씩 줄입니다 (주황 → 빨강으로 바뀜).
            // 큐에서 꺼내서 필드에 얹지는 않습니다 — 실제로 얹히는 건
            // 플레이어가 하드드롭할 때(resolve_turn_garbage)뿐이고, 그때까지
            // 빨강이 된 가비지는 큐에 그대로 남아 있습니다. AI가 이번에 새로
            // 보내는 가비지는 아래 ai_take_turn에서 들어오므로 이 틱에는
            // 안 걸립니다 (일반 라운드랑 같은 순서).
            for chunk in inner.player_garbage_queue.iter_mut() {
                chunk.delay_remaining = chunk.delay_remaining.saturating_sub(1);
            }
        } // 락을 풀고 나서 ai_take_turn이 다시 잡습니다 (Mutex는 재진입 불가).
        self.ai_take_turn()
    }

    /// 직전 라운드(내 하드드롭 + 그 뒤 AI 응수) 시작 시점으로 되돌립니다.
    /// 되돌릴 게 없으면 false.
    pub fn undo(&self) -> bool {
        let mut inner = self.inner.lock().unwrap();
        if let Some(snap) = inner.history.pop_back() {
            restore_snapshot(&mut inner, snap);
            true
        } else {
            false
        }
    }

    /// 언도 가능한 횟수 (버튼 비활성화 등 UI 처리에 사용).
    pub fn get_undo_count(&self) -> u32 {
        self.inner.lock().unwrap().history.len() as u32
    }

    /// 여러 줄 가비지가 한 번에 얹힐 때, 이전 줄과 같은 칸에 뚫릴 확률을
    /// 설정합니다 (0.0~1.0, 범위 밖 값은 안쪽으로 자동 조정됩니다).
    pub fn set_garbage_same_column_chance(&self, chance: f64) {
        let mut inner = self.inner.lock().unwrap();
        inner.garbage_same_column_chance = chance.clamp(0.0, 1.0);
    }

    /// 가비지가 도착한 후 활성화(실제로 필드에 얹힘)까지 몇 턴 대기하는지.
    /// 0이면 즉시 다음 턴에 바로 적용됩니다.
    pub fn set_garbage_delay(&self, value: u32) {
        self.inner.lock().unwrap().garbage_delay = value;
    }
    pub fn get_garbage_delay(&self) -> u32 {
        self.inner.lock().unwrap().garbage_delay
    }

    /// 지금 설정된 가비지 직렬 확률.
    pub fn get_garbage_same_column_chance(&self) -> f64 {
        self.inner.lock().unwrap().garbage_same_column_chance
    }

    /// AI가 최소 이만큼은 반드시 탐색하고 답을 내놓게 하는 값.
    /// 너무 낮으면(0에 가까우면) 대충 첫 후보로 답하는 문제가 생길 수 있습니다.
    pub fn set_ai_min_nodes(&self, value: u32) {
        self.inner.lock().unwrap().ai_min_nodes = value;
    }
    pub fn get_ai_min_nodes(&self) -> u32 {
        self.inner.lock().unwrap().ai_min_nodes
    }

    /// AI가 탐색할 수 있는 최대치. 높을수록 강해질 여지가 있지만 응답이
    /// 느려질 위험도 커집니다 (8초 타임아웃 안에는 들어와야 함).
    pub fn set_ai_max_nodes(&self, value: u32) {
        self.inner.lock().unwrap().ai_max_nodes = value;
    }
    pub fn get_ai_max_nodes(&self) -> u32 {
        self.inner.lock().unwrap().ai_max_nodes
    }

    /// 탐색에 쓸 스레드 수. 기기 코어 수에 맞춰 조절하면 좋습니다.
    pub fn set_ai_threads(&self, value: u32) {
        self.inner.lock().unwrap().ai_threads = value.max(1);
    }
    pub fn get_ai_threads(&self) -> u32 {
        self.inner.lock().unwrap().ai_threads
    }

    /// 아직 안 보이는 미래 조각까지 확률적으로 고려할지 여부.
    pub fn set_ai_speculate(&self, value: bool) {
        self.inner.lock().unwrap().ai_speculate = value;
    }
    pub fn get_ai_speculate(&self) -> bool {
        self.inner.lock().unwrap().ai_speculate
    }

    /// 지금 게임이 끝났는지 (둘 중 하나라도 탑아웃됐는지) 여부.
    pub fn is_game_over(&self) -> bool {
        self.inner.lock().unwrap().game_over
    }

    /// 게임이 끝났다면 어느 쪽이 탑아웃됐는지 (결과 화면 승/패 판정용).
    pub fn get_game_over_side(&self) -> Option<GameOverSide> {
        self.inner.lock().unwrap().topped_out_side
    }

    /// 이번 판 누적 통계 (결과 화면용). 게임 도중에 불러도 그때까지의
    /// 누적값을 그대로 돌려줍니다.
    pub fn get_match_stats(&self) -> MatchStats {
        self.inner.lock().unwrap().stats.clone()
    }

    /// 화면에 그릴 내 필드. 10칸(가로) x 24칸(세로), 아래서부터 위로 순서.
    /// 지금 조작 중인 조각도 같이 겹쳐서 넣어줍니다 (안 그러면 좌우로 움직여도
    /// 화면엔 아무 변화가 안 보입니다).
    pub fn get_player_field(&self) -> Vec<CellKind> {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        let mut field = board_field_grid(&inner.player_board);
        if let Some(piece) = &inner.player_falling {
            let kind: CellKind = piece.kind.0.into();
            for (x, y) in piece.cells() {
                if x >= 0 && (x as usize) < 10 && y >= 0 && (y as usize) < VISIBLE_ROWS {
                    field[y as usize][x as usize] = kind;
                }
            }
        }
        flatten(field)
    }

    /// 지금 조작 중인 조각을 그대로 하드드롭했을 때 닿을 위치(고스트).
    /// 조작 중인 조각이 없거나 게임이 끝났으면 전부 Empty로 채워서 돌려줍니다.
    pub fn get_player_ghost_field(&self) -> Vec<CellKind> {
        let mut inner = self.inner.lock().unwrap();
        ensure_player_piece(&mut inner);
        let mut grid = [[CellKind::Empty; 10]; VISIBLE_ROWS];
        if let Some(piece) = &inner.player_falling {
            // FallingPiece는 Copy라 그냥 복사해서 떨어뜨려보고, 원본은 안 건드립니다.
            let mut ghost = *piece;
            ghost.sonic_drop(&inner.player_board);
            let kind: CellKind = ghost.kind.0.into();
            for (x, y) in ghost.cells() {
                if x >= 0 && (x as usize) < 10 && y >= 0 && (y as usize) < VISIBLE_ROWS {
                    grid[y as usize][x as usize] = kind;
                }
            }
        }
        flatten(grid)
    }

    /// 힌트: 지금 내 필드+큐를 그대로 봇한테 줬을 때, 봇이라면 지금 조각을
    /// 어디에 둘지 보여줍니다. 계산이 오래 걸릴 수 있어서(AI 턴과 동일한
    /// 예산) 안드로이드에서는 반드시 백그라운드 스레드에서 호출해야 하고,
    /// 계산 중에도 게임 자체는 멈추지 않도록 락은 필요한 정보만 복사해온
    /// 뒤 곧바로 풀어둡니다.
    pub fn get_player_hint_field(&self) -> Vec<CellKind> {
        let (plain_board, options, game_over, incoming) = {
            let mut inner = self.inner.lock().unwrap();
            ensure_player_piece(&mut inner);
            let current_piece = inner.player_falling.map(|p| p.kind.0);
            let plain_board = plain_board_from_colored(&inner.player_board, current_piece);
            let options = bot_options(
                inner.ai_min_nodes,
                inner.ai_max_nodes,
                inner.ai_threads,
                inner.ai_speculate,
            );
            let incoming = total_pending_garbage(&inner.player_garbage_queue);
            (plain_board, options, inner.game_over, incoming)
        }; // 락은 여기서 풀림 — 아래 계산 도중엔 다른 조작이 안 막힙니다.

        let mut grid = [[CellKind::Empty; 10]; VISIBLE_ROWS];
        if game_over {
            return flatten(grid);
        }

        let bot = Interface::launch(plain_board, options, Standard::default(), None);
        bot.suggest_next_move(incoming);
        let result = wait_for_move(&bot);
        drop(bot);

        if let Some((mv, _info)) = result {
            let kind: CellKind = mv.expected_location.kind.0.into();
            for (x, y) in mv.expected_location.cells() {
                if x >= 0 && (x as usize) < 10 && y >= 0 && (y as usize) < VISIBLE_ROWS {
                    grid[y as usize][x as usize] = kind;
                }
            }
        }
        flatten(grid)
    }

    /// 힌트를 여러 수 앞까지 미리 보여줍니다. 한 걸음씩 실제로 시뮬레이션
    /// 보드에 두면서 진행하는데, **라인이 지워지는 수가 나오면 그 수까지만
    /// 포함하고 거기서 멈춥니다** — 그 이후로는 필드가 아래로 밀려서 좌표가
    /// 어긋나기 때문에, 정확한 범위까지만 보여주는 게 낫습니다.
    ///
    /// 매 단계마다 봇을 새로 띄우기 때문에 depth가 클수록 그만큼 오래
    /// 걸립니다 (예: depth=5면 대략 AI 한 턴 계산의 5배 정도).
    pub fn get_player_hint_plan(&self, depth: u32) -> Vec<HintStep> {
        let (mut board, options, game_over, current_piece, incoming) = {
            let mut inner = self.inner.lock().unwrap();
            ensure_player_piece(&mut inner);
            let current_piece = inner.player_falling.map(|p| p.kind.0);
            let board = plain_board_from_colored(&inner.player_board, current_piece);
            let options = bot_options(
                inner.ai_min_nodes,
                inner.ai_max_nodes,
                inner.ai_threads,
                inner.ai_speculate,
            );
            let incoming = total_pending_garbage(&inner.player_garbage_queue);
            (board, options, inner.game_over, current_piece, incoming)
        }; // 락은 여기서 풀림 — 계산 도중엔 다른 조작이 안 막힙니다.

        let mut steps = Vec::new();
        if game_over || current_piece.is_none() {
            return steps;
        }

        // 지금까지(이전 단계들 포함) 어떤 "원래(현재 필드 기준) 줄"이
        // 지워졌는지 누적해서 집합으로 들고 있습니다. 이걸로 매 단계마다
        // "시뮬레이션 안에서의 줄 번호 -> 원래 줄 번호" 대응표를 새로
        // 만들어서, 클리어가 여러 번 겹치거나 조각이 클리어 경계에 걸쳐도
        // 정확한 위치(끊어진 모양 포함)를 계산합니다. 숫자 하나를 통째로
        // 더하는 방식으로는 이런 경우를 정확히 표현할 수 없었습니다.
        let mut removed_original: std::collections::HashSet<i32> = std::collections::HashSet::new();

        for d in 0..depth {
            // 시뮬레이션할 조각 자체가 없으면(큐 소진) 없는 조각을 지어내지
            // 않고 여기서 계획을 멈춥니다.
            if board.get_next_piece().is_err() {
                break;
            }

            // 지금까지 지워진 줄들을 감안한 대응표. index = 시뮬레이션 줄
            // 번호, 값 = 그게 원래(현재 필드) 몇 번째 줄이었는지.
            let original_of_sim: Vec<i32> =
                (0..40).filter(|o| !removed_original.contains(o)).collect();

            let bot = Interface::launch(board.clone(), options, Standard::default(), None);
            // 첫 단계는 지금 실제로 쌓여있는 가비지 양을 그대로 알려줍니다.
            // 그 다음 단계들은 이 미리보기 시뮬레이션이 미래의 가비지 큐
            // 변화까지는 추적하지 않아서 0으로 둡니다.
            let step_incoming = if d == 0 { incoming } else { 0 };
            bot.suggest_next_move(step_incoming);
            let result = wait_for_move(&bot);
            drop(bot);

            let (mv, _info) = match result {
                Some(r) => r,
                None => break, // 이번 단계 응답이 없으면 여기서 계획 중단
            };

            // 락 전에 조각 칸 좌표를 미리 기억해둡니다 (락 이후엔 지워진 줄이
            // 반영된 필드만 남아서, 원래 이 조각이 어디 있었는지는 이걸로
            // 판단해야 합니다).
            let piece_cells = mv.expected_location.cells();
            let kind: CellKind = mv.expected_location.kind.0.into();

            // 다음 단계 계산을 위해 시뮬레이션 보드에 이번 수를 실제로 반영.
            if mv.hold {
                let stashed = board.get_next_piece().unwrap();
                board.advance_queue();
                if board.hold(stashed).is_none() {
                    board.advance_queue();
                }
            } else {
                board.advance_queue();
            }
            let mut placement = mv.expected_location;
            placement.sonic_drop(&board);
            let lock_result = board.lock_piece(placement);

            // 이번 수 자신이 지운 줄들(시뮬레이션 기준)을 원래 줄 번호로
            // 바꿔서 누적 집합에 추가 — 다음 단계들의 대응표에 반영됩니다.
            for &cleared_sim_y in lock_result.cleared_lines.iter() {
                if let Some(&orig) = original_of_sim.get(cleared_sim_y as usize) {
                    removed_original.insert(orig);
                }
            }

            // 렌더링: 이번 조각은 (이번 수 자신의 클리어를 아직 반영하지
            // 않은) 대응표로 원래 줄 번호에 온전히 그립니다 — 지워지는
            // 칸이라도 건너뛰지 않고 "이 조각이 실제로 어떤 모양으로
            // 놓였는지" 그대로 보여줍니다. 위치 보정(칸이 밀리는 것)은
            // 다음 단계부터 반영됩니다.
            let mut grid = [[CellKind::Empty; 10]; VISIBLE_ROWS];
            for (x, y) in piece_cells {
                if let Some(&orig_y) = original_of_sim.get(y as usize) {
                    if x >= 0 && (x as usize) < 10 && orig_y >= 0 && (orig_y as usize) < VISIBLE_ROWS {
                        grid[orig_y as usize][x as usize] = kind;
                    }
                }
            }
            steps.push(HintStep { depth: d, cells: flatten(grid) });
        }

        steps
    }

    /// 화면에 그릴 AI 필드.
    pub fn get_ai_field(&self) -> Vec<CellKind> {
        let inner = self.inner.lock().unwrap();
        flatten(board_field_grid(&inner.ai_board_colored))
    }

    /// 내 다음 조각 큐 미리보기 (앞에서부터 최대 5개).
    pub fn get_player_next_queue(&self) -> Vec<PieceKind> {
        let inner = self.inner.lock().unwrap();
        inner.player_board.next_queue().take(5).map(PieceKind::from).collect()
    }

    /// AI 다음 조각 큐 미리보기 (앞에서부터 최대 5개).
    pub fn get_ai_next_queue(&self) -> Vec<PieceKind> {
        let inner = self.inner.lock().unwrap();
        inner.ai_board.next_queue().take(5).map(PieceKind::from).collect()
    }

    /// 내가 받을 예정인 가비지 큐 (앞쪽이 먼저 활성화될 것들).
    pub fn get_player_garbage_queue(&self) -> Vec<GarbageEntry> {
        let inner = self.inner.lock().unwrap();
        inner
            .player_garbage_queue
            .iter()
            .map(|c| GarbageEntry { lines: c.lines, active: c.delay_remaining == 0 })
            .collect()
    }

    /// AI가 받을 예정인 가비지 큐.
    pub fn get_ai_garbage_queue(&self) -> Vec<GarbageEntry> {
        let inner = self.inner.lock().unwrap();
        inner
            .ai_garbage_queue
            .iter()
            .map(|c| GarbageEntry { lines: c.lines, active: c.delay_remaining == 0 })
            .collect()
    }

    /// 내 홀드 칸에 들어있는 조각 (없으면 None).
    pub fn get_player_hold(&self) -> Option<PieceKind> {
        let inner = self.inner.lock().unwrap();
        inner.player_board.hold_piece.map(PieceKind::from)
    }

    /// AI 홀드 칸에 들어있는 조각 (없으면 None).
    pub fn get_ai_hold(&self) -> Option<PieceKind> {
        let inner = self.inner.lock().unwrap();
        inner.ai_board.hold_piece.map(PieceKind::from)
    }

    /// 파이프라인 연결 확인용 (지난 단계와 동일한 역할)
    pub fn version(&self) -> String {
        format!("app-core {} (uniffi 연동 OK)", env!("CARGO_PKG_VERSION"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plays_many_rounds_without_crashing() {
        let m = Match::new();

        for i in 0..30 {
            m.player_move_left();
            m.player_move_left();
            m.player_rotate_cw();

            let player_result = m.player_hard_drop();
            let ai_result = m.ai_take_turn();
            println!("round {i}: player={:?} ai={:?}", player_result, ai_result);

            if m.is_game_over() {
                println!("게임 종료 (라운드 {i})");
                break;
            }
        }

        let field = m.get_player_field();
        assert_eq!(field.len(), 22 * 10);

        let next = m.get_player_next_queue();
        assert!(!next.is_empty());
    }
}
