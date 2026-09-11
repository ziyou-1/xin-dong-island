package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	_ "modernc.org/sqlite"
)

// ── config ──────────────────────────────────────────────────────────

var (
	listenAddr = ":9900"
	dataDir    = "./data"
	uploadDir  = "./uploads"
	db         *sql.DB
)

// ── models ──────────────────────────────────────────────────────────

type User struct {
	ID       int    `json:"id"`
	Nickname string `json:"nickname"`
	Avatar   string `json:"avatar"`
	Token    string `json:"token"`
}

type StatusItem struct {
	ID       int    `json:"id"`
	UserID   int    `json:"user_id"`
	Name     string `json:"name"`
	Value    int    `json:"value"`
	Burst    bool   `json:"burst"`
	SortIdx  int    `json:"sort_idx"`
}

type Buff struct {
	ID       int    `json:"id"`
	UserID   int    `json:"user_id"`
	Name     string `json:"name"`
	Icon     string `json:"icon"`
	Positive bool   `json:"positive"`
	Active   bool   `json:"active"`
}

type Anniversary struct {
	ID          int    `json:"id"`
	Title       string `json:"title"`
	Description string `json:"description"`
	TargetDate  string `json:"target_date"`
	IncludeDay  bool   `json:"include_day"`
	Pinned      bool   `json:"pinned"`
	SortIdx     int    `json:"sort_idx"`
}

type Message struct {
	ID        int    `json:"id"`
	FromUser  int    `json:"from_user"`
	Content   string `json:"content"`
	Images    string `json:"images"`
	ReadAt    string `json:"read_at"`
	CreatedAt string `json:"created_at"`
}

type Task struct {
	ID         int    `json:"id"`
	CreatorID  int    `json:"creator_id"`
	AssigneeID int    `json:"assignee_id"`
	Title      string `json:"title"`
	Detail     string `json:"detail"`
	TaskType   string `json:"task_type"`
	Deadline   string `json:"deadline,omitempty"`
	Status     string `json:"status"`
	CreatedAt  string `json:"created_at"`
}

type CheckIn struct {
	ID        int    `json:"id"`
	TaskID    int    `json:"task_id"`
	UserID    int    `json:"user_id"`
	Note      string `json:"note"`
	Photo     string `json:"photo,omitempty"`
	CreatedAt string `json:"created_at"`
}

type Trip struct {
	ID        int    `json:"id"`
	UserID    int    `json:"user_id"`
	TripDate  string `json:"trip_date"`
	Route     string `json:"route,omitempty"`
	Note      string `json:"note,omitempty"`
	Images    string `json:"images"`
	CreatedAt string `json:"created_at"`
}

type Package struct {
	ID        int    `json:"id"`
	UserID    int    `json:"user_id"`
	Image     string `json:"image"`
	Note      string `json:"note,omitempty"`
	Picked    bool   `json:"picked"`
	CreatedAt string `json:"created_at"`
}

type DeviceStatus struct {
	UserID   int  `json:"user_id"`
	Battery  int  `json:"battery"`
	Charging bool `json:"charging"`
	Steps    int  `json:"steps"`
	Lat      float64 `json:"lat"`
	Lng      float64 `json:"lng"`
}

type Settings struct {
	LoveStartDate string `json:"love_start_date"`
	SplashText    string `json:"splash_text"`
}

// ── websocket hub ───────────────────────────────────────────────────

var upgrader = websocket.Upgrader{CheckOrigin: func(r *http.Request) bool { return true }}

type Hub struct {
	mu    sync.RWMutex
	conns map[int]*websocket.Conn // userID -> conn
}

var hub = &Hub{conns: make(map[int]*websocket.Conn)}

func (h *Hub) register(uid int, conn *websocket.Conn) {
	h.mu.Lock()
	if old, ok := h.conns[uid]; ok {
		old.Close()
	}
	h.conns[uid] = conn
	h.mu.Unlock()
}

func (h *Hub) unregister(uid int) {
	h.mu.Lock()
	delete(h.conns, uid)
	h.mu.Unlock()
}

func (h *Hub) sendTo(uid int, msg any) {
	h.mu.RLock()
	conn := h.conns[uid]
	h.mu.RUnlock()
	if conn != nil {
		data, _ := json.Marshal(msg)
		conn.WriteMessage(websocket.TextMessage, data)
	}
}

func (h *Hub) broadcast(msg any) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	data, _ := json.Marshal(msg)
	for _, conn := range h.conns {
		conn.WriteMessage(websocket.TextMessage, data)
	}
}

type WSMessage struct {
	Type string          `json:"type"`
	Data json.RawMessage `json:"data"`
}

// ── database schema ─────────────────────────────────────────────────

func initDB() {
	os.MkdirAll(dataDir, 0755)
	os.MkdirAll(uploadDir, 0755)

	var err error
	db, err = sql.Open("sqlite", filepath.Join(dataDir, "island.db"))
	if err != nil {
		log.Fatal(err)
	}
	db.Exec("PRAGMA journal_mode=WAL")
	db.Exec("PRAGMA foreign_keys=ON")

	schema := `
CREATE TABLE IF NOT EXISTS users (
	id INTEGER PRIMARY KEY,
	nickname TEXT NOT NULL,
	avatar TEXT DEFAULT '',
	token TEXT UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
	key TEXT PRIMARY KEY,
	value TEXT
);
CREATE TABLE IF NOT EXISTS status_items (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL,
	name TEXT NOT NULL,
	value INTEGER DEFAULT 50,
	burst INTEGER DEFAULT 0,
	sort_idx INTEGER DEFAULT 0,
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS buffs (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL,
	name TEXT NOT NULL,
	icon TEXT DEFAULT '⭐',
	positive INTEGER DEFAULT 1,
	active INTEGER DEFAULT 0,
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS anniversaries (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	title TEXT NOT NULL,
	description TEXT DEFAULT '',
	target_date TEXT NOT NULL,
	include_day INTEGER DEFAULT 0,
	pinned INTEGER DEFAULT 0,
	sort_idx INTEGER DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	from_user INTEGER NOT NULL,
	content TEXT DEFAULT '',
	images TEXT DEFAULT '[]',
	read_at TEXT DEFAULT '',
	created_at TEXT NOT NULL,
	FOREIGN KEY(from_user) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS popup_messages (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	from_user INTEGER NOT NULL,
	content TEXT NOT NULL,
	created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS tasks (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	creator_id INTEGER NOT NULL,
	assignee_id INTEGER NOT NULL,
	title TEXT NOT NULL,
	detail TEXT DEFAULT '',
	task_type TEXT DEFAULT 'once',
	deadline TEXT DEFAULT '',
	status TEXT DEFAULT 'pending',
	created_at TEXT NOT NULL,
	FOREIGN KEY(creator_id) REFERENCES users(id),
	FOREIGN KEY(assignee_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS checkins (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	task_id INTEGER NOT NULL,
	user_id INTEGER NOT NULL,
	note TEXT DEFAULT '',
	photo TEXT DEFAULT '',
	created_at TEXT NOT NULL,
	FOREIGN KEY(task_id) REFERENCES tasks(id),
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS trips (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL,
	trip_date TEXT NOT NULL,
	route TEXT DEFAULT '',
	note TEXT DEFAULT '',
	images TEXT DEFAULT '[]',
	created_at TEXT NOT NULL,
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS packages (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	user_id INTEGER NOT NULL,
	image TEXT DEFAULT '',
	note TEXT DEFAULT '',
	picked INTEGER DEFAULT 0,
	created_at TEXT NOT NULL,
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS device_status (
	user_id INTEGER PRIMARY KEY,
	battery INTEGER DEFAULT -1,
	charging INTEGER DEFAULT 0,
	steps INTEGER DEFAULT 0,
	lat REAL DEFAULT 0,
	lng REAL DEFAULT 0,
	updated_at TEXT DEFAULT '',
	FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS privacy_settings (
	user_id INTEGER PRIMARY KEY,
	show_battery INTEGER DEFAULT 1,
	show_charging INTEGER DEFAULT 1,
	show_steps INTEGER DEFAULT 1,
	show_location INTEGER DEFAULT 1,
	notify_message INTEGER DEFAULT 1,
	notify_task INTEGER DEFAULT 1,
	notify_trip INTEGER DEFAULT 1,
	notify_package INTEGER DEFAULT 1,
	vibrate INTEGER DEFAULT 1,
	FOREIGN KEY(user_id) REFERENCES users(id)
);`
	for _, stmt := range strings.Split(schema, ";") {
		stmt = strings.TrimSpace(stmt)
		if stmt != "" {
			if _, err := db.Exec(stmt); err != nil {
				log.Printf("schema exec: %v\n%s", err, stmt)
			}
		}
	}
}

func seedData() {
	var count int
	db.QueryRow("SELECT COUNT(*) FROM users").Scan(&count)
	if count > 0 {
		return
	}

	tok1 := randomToken()
	tok2 := randomToken()
	db.Exec("INSERT INTO users(id,nickname,avatar,token) VALUES(1,'小鹿','',?)", tok1)
	db.Exec("INSERT INTO users(id,nickname,avatar,token) VALUES(2,'大熊','',?)", tok2)

	db.Exec("INSERT INTO settings(key,value) VALUES('love_start_date','2024-01-01')")
	db.Exec("INSERT INTO settings(key,value) VALUES('splash_text','今天也好想你 ❤️')")

	statusNames := []string{"想你值", "心情", "委屈值", "快乐值", "疲惫值"}
	for _, uid := range []int{1, 2} {
		for i, name := range statusNames {
			db.Exec("INSERT INTO status_items(user_id,name,value,sort_idx) VALUES(?,?,50,?)", uid, name, i)
		}
	}

	posBuffs := []struct{ name, icon string }{
		{"被夸了", "🥰"}, {"吃到好吃的", "😋"}, {"收到礼物", "🎁"},
		{"约会日", "💕"}, {"被抱抱", "🤗"}, {"睡了好觉", "😴"},
	}
	negBuffs := []struct{ name, icon string }{
		{"加班", "😵"}, {"生理期", "🩹"}, {"emo", "😢"},
		{"生气", "😤"}, {"失眠", "🌙"}, {"想家", "🏠"},
	}
	for _, uid := range []int{1, 2} {
		for _, b := range posBuffs {
			db.Exec("INSERT INTO buffs(user_id,name,icon,positive) VALUES(?,?,?,1)", uid, b.name, b.icon)
		}
		for _, b := range negBuffs {
			db.Exec("INSERT INTO buffs(user_id,name,icon,positive) VALUES(?,?,?,0)", uid, b.name, b.icon)
		}
	}

	db.Exec("INSERT INTO anniversaries(title,description,target_date,pinned,sort_idx) VALUES('在一起啦','我们在一起的第一天','2024-01-01',1,0)")
	db.Exec("INSERT INTO anniversaries(title,description,target_date,sort_idx) VALUES('一周年','第一个纪念日','2025-01-01',1)")
	db.Exec("INSERT INTO anniversaries(title,description,target_date,sort_idx) VALUES('两周年','','2026-01-01',2)")

	for _, uid := range []int{1, 2} {
		db.Exec("INSERT INTO device_status(user_id) VALUES(?)", uid)
		db.Exec("INSERT INTO privacy_settings(user_id) VALUES(?)", uid)
	}

	log.Printf("Seeded placeholder data. User tokens:\n  小鹿 (id=1): %s\n  大熊 (id=2): %s\n", tok1, tok2)
}

func randomToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// ── auth middleware ──────────────────────────────────────────────────

func authUser(r *http.Request) (*User, error) {
	token := r.Header.Get("X-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	if token == "" {
		return nil, fmt.Errorf("missing token")
	}
	var u User
	err := db.QueryRow("SELECT id,nickname,avatar,token FROM users WHERE token=?", token).Scan(&u.ID, &u.Nickname, &u.Avatar, &u.Token)
	if err != nil {
		return nil, fmt.Errorf("invalid token")
	}
	return &u, nil
}

func partnerID(uid int) int {
	if uid == 1 {
		return 2
	}
	return 1
}

func jsonResp(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func errResp(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func now() string {
	return time.Now().Format(time.RFC3339)
}

// ── handlers ────────────────────────────────────────────────────────

func handleLogin(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	jsonResp(w, u)
}

// -- settings --

func handleGetSettings(w http.ResponseWriter, r *http.Request) {
	rows, _ := db.Query("SELECT key,value FROM settings")
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var k, v string
		rows.Scan(&k, &v)
		m[k] = v
	}
	jsonResp(w, m)
}

func handleUpdateSettings(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var m map[string]string
	json.NewDecoder(r.Body).Decode(&m)
	for k, v := range m {
		db.Exec("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=?", k, v, v)
	}
	hub.broadcast(map[string]any{"type": "settings_updated", "data": m})
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- users --

func handleGetUsers(w http.ResponseWriter, r *http.Request) {
	rows, _ := db.Query("SELECT id,nickname,avatar FROM users ORDER BY id")
	defer rows.Close()
	var users []map[string]any
	for rows.Next() {
		var id int
		var nick, avatar string
		rows.Scan(&id, &nick, &avatar)
		users = append(users, map[string]any{"id": id, "nickname": nick, "avatar": avatar})
	}
	jsonResp(w, users)
}

func handleUpdateUser(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	if body.Nickname != "" {
		db.Exec("UPDATE users SET nickname=? WHERE id=?", body.Nickname, u.ID)
	}
	if body.Avatar != "" {
		db.Exec("UPDATE users SET avatar=? WHERE id=?", body.Avatar, u.ID)
	}
	hub.broadcast(map[string]any{"type": "user_updated", "data": map[string]any{"user_id": u.ID}})
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- status items --

func handleGetStatus(w http.ResponseWriter, r *http.Request) {
	rows, _ := db.Query("SELECT id,user_id,name,value,burst,sort_idx FROM status_items ORDER BY sort_idx")
	defer rows.Close()
	var items []StatusItem
	for rows.Next() {
		var s StatusItem
		var burst int
		rows.Scan(&s.ID, &s.UserID, &s.Name, &s.Value, &burst, &s.SortIdx)
		s.Burst = burst == 1
		items = append(items, s)
	}
	jsonResp(w, items)
}

func handleUpdateStatus(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		ID    int  `json:"id"`
		Value int  `json:"value"`
		Burst bool `json:"burst"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	burstInt := 0
	if body.Burst {
		burstInt = 1
	}
	db.Exec("UPDATE status_items SET value=?,burst=? WHERE id=? AND user_id=?", body.Value, burstInt, body.ID, u.ID)
	hub.broadcast(map[string]any{"type": "status_updated", "data": body})
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleAddStatus(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		Name  string `json:"name"`
		Value int    `json:"value"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	if body.Value <= 0 || body.Value > 100 {
		body.Value = 50
	}
	res, _ := db.Exec("INSERT INTO status_items(user_id,name,value,sort_idx) VALUES(?,?,?,99)", u.ID, body.Name, body.Value)
	id, _ := res.LastInsertId()
	jsonResp(w, map[string]any{"id": id})
}

func handleDeleteStatus(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	idStr := r.URL.Query().Get("id")
	db.Exec("DELETE FROM status_items WHERE id=? AND user_id=?", idStr, u.ID)
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- buffs --

func handleGetBuffs(w http.ResponseWriter, r *http.Request) {
	rows, _ := db.Query("SELECT id,user_id,name,icon,positive,active FROM buffs ORDER BY id")
	defer rows.Close()
	var items []Buff
	for rows.Next() {
		var b Buff
		var pos, act int
		rows.Scan(&b.ID, &b.UserID, &b.Name, &b.Icon, &pos, &act)
		b.Positive = pos == 1
		b.Active = act == 1
		items = append(items, b)
	}
	jsonResp(w, items)
}

func handleToggleBuff(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		ID     int  `json:"id"`
		Active bool `json:"active"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	act := 0
	if body.Active {
		act = 1
	}
	db.Exec("UPDATE buffs SET active=? WHERE id=? AND user_id=?", act, body.ID, u.ID)
	hub.broadcast(map[string]any{"type": "buff_updated", "data": map[string]any{"user_id": u.ID, "buff_id": body.ID, "active": body.Active}})
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleAddBuff(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		Name     string `json:"name"`
		Icon     string `json:"icon"`
		Positive bool   `json:"positive"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	pos := 0
	if body.Positive {
		pos = 1
	}
	res, _ := db.Exec("INSERT INTO buffs(user_id,name,icon,positive) VALUES(?,?,?,?)", u.ID, body.Name, body.Icon, pos)
	id, _ := res.LastInsertId()
	jsonResp(w, map[string]any{"id": id})
}

// -- anniversaries --

func handleGetAnniversaries(w http.ResponseWriter, r *http.Request) {
	rows, _ := db.Query("SELECT id,title,description,target_date,include_day,pinned,sort_idx FROM anniversaries ORDER BY pinned DESC, sort_idx")
	defer rows.Close()
	var items []Anniversary
	for rows.Next() {
		var a Anniversary
		var inc, pin int
		rows.Scan(&a.ID, &a.Title, &a.Description, &a.TargetDate, &inc, &pin, &a.SortIdx)
		a.IncludeDay = inc == 1
		a.Pinned = pin == 1
		items = append(items, a)
	}
	jsonResp(w, items)
}

func handleCreateAnniversary(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var a Anniversary
	json.NewDecoder(r.Body).Decode(&a)
	inc, pin := 0, 0
	if a.IncludeDay {
		inc = 1
	}
	if a.Pinned {
		pin = 1
	}
	res, _ := db.Exec("INSERT INTO anniversaries(title,description,target_date,include_day,pinned,sort_idx) VALUES(?,?,?,?,?,99)",
		a.Title, a.Description, a.TargetDate, inc, pin)
	id, _ := res.LastInsertId()
	jsonResp(w, map[string]any{"id": id})
}

func handleDeleteAnniversary(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	idStr := r.URL.Query().Get("id")
	db.Exec("DELETE FROM anniversaries WHERE id=?", idStr)
	jsonResp(w, map[string]string{"ok": "true"})
}

func handlePinAnniversary(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		ID     int  `json:"id"`
		Pinned bool `json:"pinned"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	pin := 0
	if body.Pinned {
		pin = 1
	}
	db.Exec("UPDATE anniversaries SET pinned=? WHERE id=?", pin, body.ID)
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- messages (留言板) --

func handleGetMessages(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	month := r.URL.Query().Get("month")
	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	limit := 20
	offset := (page - 1) * limit
	var rows *sql.Rows
	var err error
	if month != "" {
		rows, err = db.Query("SELECT id,from_user,content,images,read_at,created_at FROM messages WHERE created_at LIKE ? ORDER BY created_at DESC", month+"%")
	} else {
		rows, err = db.Query("SELECT id,from_user,content,images,read_at,created_at FROM messages ORDER BY created_at DESC LIMIT ? OFFSET ?", limit, offset)
	}
	if err != nil {
		errResp(w, 500, err.Error())
		return
	}
	defer rows.Close()
	var msgs []Message
	for rows.Next() {
		var m Message
		var readAt, images sql.NullString
		if scanErr := rows.Scan(&m.ID, &m.FromUser, &m.Content, &images, &readAt, &m.CreatedAt); scanErr != nil {
			continue
		}
		if images.Valid {
			m.Images = images.String
		}
		if readAt.Valid {
			m.ReadAt = readAt.String
		}
		msgs = append(msgs, m)
	}
	if msgs == nil {
		msgs = []Message{}
	}
	jsonResp(w, msgs)
}

func handlePostMessage(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		Content string   `json:"content"`
		Images  []string `json:"images"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	imgs, _ := json.Marshal(body.Images)
	res, _ := db.Exec("INSERT INTO messages(from_user,content,images,created_at) VALUES(?,?,?,?)", u.ID, body.Content, string(imgs), now())
	id, _ := res.LastInsertId()
	hub.sendTo(partnerID(u.ID), map[string]any{"type": "new_message", "data": map[string]any{"id": id, "from_user": u.ID}})
	jsonResp(w, map[string]any{"id": id})
}

func handleMarkRead(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		ID int `json:"id"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	db.Exec("UPDATE messages SET read_at=? WHERE id=? AND from_user!=?", now(), body.ID, u.ID)
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- popup messages (即时弹窗) --

func handleSendPopup(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		Content string `json:"content"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	db.Exec("INSERT INTO popup_messages(from_user,content,created_at) VALUES(?,?,?)", u.ID, body.Content, now())
	hub.sendTo(partnerID(u.ID), map[string]any{"type": "popup", "data": map[string]any{"from_user": u.ID, "content": body.Content}})
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- tasks --

func handleGetTasks(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	rows, _ := db.Query("SELECT id,creator_id,assignee_id,title,detail,task_type,deadline,status,created_at FROM tasks ORDER BY created_at DESC")
	defer rows.Close()
	var tasks []Task
	for rows.Next() {
		var t Task
		rows.Scan(&t.ID, &t.CreatorID, &t.AssigneeID, &t.Title, &t.Detail, &t.TaskType, &t.Deadline, &t.Status, &t.CreatedAt)
		tasks = append(tasks, t)
	}
	if tasks == nil {
		tasks = []Task{}
	}
	jsonResp(w, tasks)
}

func handleCreateTask(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var t Task
	json.NewDecoder(r.Body).Decode(&t)
	t.CreatorID = u.ID
	if t.AssigneeID == 0 {
		t.AssigneeID = u.ID
	}
	if t.TaskType == "" {
		t.TaskType = "once"
	}
	res, _ := db.Exec("INSERT INTO tasks(creator_id,assignee_id,title,detail,task_type,deadline,status,created_at) VALUES(?,?,?,?,?,?,'pending',?)",
		t.CreatorID, t.AssigneeID, t.Title, t.Detail, t.TaskType, t.Deadline, now())
	id, _ := res.LastInsertId()
	if t.AssigneeID != u.ID {
		hub.sendTo(t.AssigneeID, map[string]any{"type": "new_task", "data": map[string]any{"id": id, "title": t.Title}})
	}
	jsonResp(w, map[string]any{"id": id})
}

func handleCheckIn(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		TaskID int    `json:"task_id"`
		Note   string `json:"note"`
		Photo  string `json:"photo"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	db.Exec("INSERT INTO checkins(task_id,user_id,note,photo,created_at) VALUES(?,?,?,?,?)", body.TaskID, u.ID, body.Note, body.Photo, now())
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleConfirmTask(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		TaskID int `json:"task_id"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	db.Exec("UPDATE tasks SET status='done' WHERE id=? AND creator_id=?", body.TaskID, u.ID)
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleGetCheckins(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	taskID := r.URL.Query().Get("task_id")
	var rows *sql.Rows
	if taskID == "" {
		rows, _ = db.Query("SELECT id,task_id,user_id,note,photo,created_at FROM checkins ORDER BY created_at DESC")
	} else {
		rows, _ = db.Query("SELECT id,task_id,user_id,note,photo,created_at FROM checkins WHERE task_id=? ORDER BY created_at DESC", taskID)
	}
	defer rows.Close()
	var items []CheckIn
	for rows.Next() {
		var c CheckIn
		rows.Scan(&c.ID, &c.TaskID, &c.UserID, &c.Note, &c.Photo, &c.CreatedAt)
		items = append(items, c)
	}
	if items == nil {
		items = []CheckIn{}
	}
	jsonResp(w, items)
}

// -- trips --

func handleGetTrips(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	rows, _ := db.Query("SELECT id,user_id,trip_date,route,note,images,created_at FROM trips ORDER BY trip_date DESC")
	defer rows.Close()
	var items []Trip
	for rows.Next() {
		var t Trip
		rows.Scan(&t.ID, &t.UserID, &t.TripDate, &t.Route, &t.Note, &t.Images, &t.CreatedAt)
		items = append(items, t)
	}
	if items == nil {
		items = []Trip{}
	}
	jsonResp(w, items)
}

func handleCreateTrip(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var t Trip
	json.NewDecoder(r.Body).Decode(&t)
	if t.Images == "" {
		t.Images = "[]"
	}
	res, _ := db.Exec("INSERT INTO trips(user_id,trip_date,route,note,images,created_at) VALUES(?,?,?,?,?,?)",
		u.ID, t.TripDate, t.Route, t.Note, t.Images, now())
	id, _ := res.LastInsertId()
	hub.sendTo(partnerID(u.ID), map[string]any{"type": "new_trip", "data": map[string]any{"id": id}})
	jsonResp(w, map[string]any{"id": id})
}

// -- packages --

func handleGetPackages(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	rows, _ := db.Query("SELECT id,user_id,image,note,picked,created_at FROM packages ORDER BY created_at DESC")
	defer rows.Close()
	var items []Package
	for rows.Next() {
		var p Package
		var picked int
		rows.Scan(&p.ID, &p.UserID, &p.Image, &p.Note, &picked, &p.CreatedAt)
		p.Picked = picked == 1
		items = append(items, p)
	}
	if items == nil {
		items = []Package{}
	}
	jsonResp(w, items)
}

func handleCreatePackage(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var p Package
	json.NewDecoder(r.Body).Decode(&p)
	res, _ := db.Exec("INSERT INTO packages(user_id,image,note,created_at) VALUES(?,?,?,?)", u.ID, p.Image, p.Note, now())
	id, _ := res.LastInsertId()
	hub.sendTo(partnerID(u.ID), map[string]any{"type": "new_package", "data": map[string]any{"id": id}})
	jsonResp(w, map[string]any{"id": id})
}

func handlePickPackage(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var body struct {
		ID int `json:"id"`
	}
	json.NewDecoder(r.Body).Decode(&body)
	db.Exec("UPDATE packages SET picked=1 WHERE id=?", body.ID)
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleClearPackages(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	db.Exec("DELETE FROM packages WHERE picked=1")
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- device status --

func handleUpdateDeviceStatus(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var s DeviceStatus
	json.NewDecoder(r.Body).Decode(&s)
	db.Exec(`INSERT INTO device_status(user_id,battery,charging,steps,lat,lng,updated_at) VALUES(?,?,?,?,?,?,?)
		ON CONFLICT(user_id) DO UPDATE SET battery=?,charging=?,steps=?,lat=?,lng=?,updated_at=?`,
		u.ID, s.Battery, boolToInt(s.Charging), s.Steps, s.Lat, s.Lng, now(),
		s.Battery, boolToInt(s.Charging), s.Steps, s.Lat, s.Lng, now())
	hub.sendTo(partnerID(u.ID), map[string]any{"type": "device_status", "data": s})
	jsonResp(w, map[string]string{"ok": "true"})
}

func handleGetDeviceStatus(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	rows, _ := db.Query("SELECT user_id,battery,charging,steps,lat,lng FROM device_status")
	defer rows.Close()
	var items []DeviceStatus
	for rows.Next() {
		var s DeviceStatus
		var charging int
		rows.Scan(&s.UserID, &s.Battery, &charging, &s.Steps, &s.Lat, &s.Lng)
		s.Charging = charging == 1
		items = append(items, s)
	}
	jsonResp(w, items)
}

// -- privacy settings --

func handleGetPrivacy(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	row := db.QueryRow("SELECT show_battery,show_charging,show_steps,show_location,notify_message,notify_task,notify_trip,notify_package,vibrate FROM privacy_settings WHERE user_id=?", u.ID)
	var sb, sc, ss, sl, nm, nt, ntr, np, vib int
	row.Scan(&sb, &sc, &ss, &sl, &nm, &nt, &ntr, &np, &vib)
	jsonResp(w, map[string]bool{
		"show_battery": sb == 1, "show_charging": sc == 1, "show_steps": ss == 1, "show_location": sl == 1,
		"notify_message": nm == 1, "notify_task": nt == 1, "notify_trip": ntr == 1, "notify_package": np == 1, "vibrate": vib == 1,
	})
}

func handleUpdatePrivacy(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	var m map[string]bool
	json.NewDecoder(r.Body).Decode(&m)
	for k, v := range m {
		allowed := map[string]bool{"show_battery": true, "show_charging": true, "show_steps": true, "show_location": true,
			"notify_message": true, "notify_task": true, "notify_trip": true, "notify_package": true, "vibrate": true}
		if allowed[k] {
			db.Exec(fmt.Sprintf("UPDATE privacy_settings SET %s=? WHERE user_id=?", k), boolToInt(v), u.ID)
		}
	}
	jsonResp(w, map[string]string{"ok": "true"})
}

// -- file upload --

func handleUpload(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	r.ParseMultipartForm(32 << 20)
	file, header, err := r.FormFile("file")
	if err != nil {
		errResp(w, 400, "no file")
		return
	}
	defer file.Close()
	ext := filepath.Ext(header.Filename)
	name := fmt.Sprintf("%d%s", time.Now().UnixNano(), ext)
	dst, _ := os.Create(filepath.Join(uploadDir, name))
	defer dst.Close()
	io.Copy(dst, file)
	jsonResp(w, map[string]string{"url": "/uploads/" + name})
}

// -- websocket --

func handleWS(w http.ResponseWriter, r *http.Request) {
	u, err := authUser(r)
	if err != nil {
		errResp(w, 401, err.Error())
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	hub.register(u.ID, conn)
	log.Printf("WS connected: user %d (%s)", u.ID, u.Nickname)
	defer func() {
		hub.unregister(u.ID)
		conn.Close()
		log.Printf("WS disconnected: user %d", u.ID)
	}()

	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			break
		}
		var msg WSMessage
		if json.Unmarshal(raw, &msg) != nil {
			continue
		}
		switch msg.Type {
		case "ping":
			conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"pong"}`))
		case "device_status":
			var s DeviceStatus
			json.Unmarshal(msg.Data, &s)
			s.UserID = u.ID
			db.Exec(`INSERT INTO device_status(user_id,battery,charging,steps,lat,lng,updated_at) VALUES(?,?,?,?,?,?,?)
				ON CONFLICT(user_id) DO UPDATE SET battery=?,charging=?,steps=?,lat=?,lng=?,updated_at=?`,
				u.ID, s.Battery, boolToInt(s.Charging), s.Steps, s.Lat, s.Lng, now(),
				s.Battery, boolToInt(s.Charging), s.Steps, s.Lat, s.Lng, now())
			hub.sendTo(partnerID(u.ID), map[string]any{"type": "device_status", "data": s})
		case "popup":
			var p struct{ Content string `json:"content"` }
			json.Unmarshal(msg.Data, &p)
			db.Exec("INSERT INTO popup_messages(from_user,content,created_at) VALUES(?,?,?)", u.ID, p.Content, now())
			hub.sendTo(partnerID(u.ID), map[string]any{"type": "popup", "data": map[string]any{"from_user": u.ID, "content": p.Content}})
		}
	}
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// -- daily task reset (background) --

func startDailyReset() {
	go snapshotBackup()
	go func() {
		for {
			nextMidnight := time.Now().Truncate(24 * time.Hour).Add(24 * time.Hour)
			time.Sleep(time.Until(nextMidnight))
			db.Exec("UPDATE tasks SET status='pending' WHERE task_type='daily' AND status='done'")
			snapshotBackup()
			log.Println("Daily tasks reset")
		}
	}()
}

func backupDir() string {
	return filepath.Join(dataDir, "backups")
}

func snapshotBackup() {
	dir := backupDir()
	if err := os.MkdirAll(dir, 0755); err != nil {
		log.Printf("backup mkdir: %v", err)
		return
	}
	dest := filepath.Join(dir, time.Now().Format("20060102")+".db")
	if _, err := os.Stat(dest); err == nil {
		return
	}
	if _, err := db.Exec("VACUUM INTO ?", dest); err != nil {
		log.Printf("backup vacuum: %v", err)
		return
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	if len(entries) <= 7 {
		return
	}
	for _, e := range entries[:len(entries)-7] {
		os.Remove(filepath.Join(dir, e.Name()))
	}
}

func handleBackup(w http.ResponseWriter, r *http.Request) {
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	tmp := filepath.Join(dataDir, "island-export.db")
	os.Remove(tmp)
	if _, err := db.Exec("VACUUM INTO ?", tmp); err != nil {
		errResp(w, 500, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", "attachment; filename=island-backup.db")
	http.ServeFile(w, r, tmp)
}

func handleRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		errResp(w, 405, "POST only")
		return
	}
	if _, err := authUser(r); err != nil {
		errResp(w, 401, err.Error())
		return
	}
	if err := r.ParseMultipartForm(64 << 20); err != nil {
		errResp(w, 400, "invalid upload")
		return
	}
	file, _, err := r.FormFile("file")
	if err != nil {
		errResp(w, 400, "no file")
		return
	}
	defer file.Close()
	incoming := filepath.Join(dataDir, "restore-in.db")
	dst, err := os.Create(incoming)
	if err != nil {
		errResp(w, 500, err.Error())
		return
	}
	if _, err := io.Copy(dst, file); err != nil {
		dst.Close()
		errResp(w, 500, err.Error())
		return
	}
	dst.Close()

	db.Exec("DETACH DATABASE bak")
	if _, err := db.Exec("ATTACH DATABASE ? AS bak", incoming); err != nil {
		errResp(w, 400, "不是有效的备份文件")
		return
	}
	tables := []string{
		"users", "settings", "status_items", "buffs", "anniversaries",
		"messages", "popup_messages", "tasks", "checkins", "trips",
		"packages", "device_status", "privacy_settings",
	}
	db.Exec("PRAGMA foreign_keys=OFF")
	tx, err := db.Begin()
	if err != nil {
		db.Exec("DETACH DATABASE bak")
		db.Exec("PRAGMA foreign_keys=ON")
		errResp(w, 500, err.Error())
		return
	}
	for _, t := range tables {
		if _, err := tx.Exec("DELETE FROM " + t); err != nil {
			tx.Rollback()
			db.Exec("DETACH DATABASE bak")
			db.Exec("PRAGMA foreign_keys=ON")
			errResp(w, 500, err.Error())
			return
		}
		if _, err := tx.Exec("INSERT INTO " + t + " SELECT * FROM bak." + t); err != nil {
			tx.Rollback()
			db.Exec("DETACH DATABASE bak")
			db.Exec("PRAGMA foreign_keys=ON")
			errResp(w, 400, "备份表不完整: "+t)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		db.Exec("DETACH DATABASE bak")
		db.Exec("PRAGMA foreign_keys=ON")
		errResp(w, 500, err.Error())
		return
	}
	db.Exec("DETACH DATABASE bak")
	db.Exec("PRAGMA foreign_keys=ON")
	jsonResp(w, map[string]string{"ok": "true"})
}

// ── main ────────────────────────────────────────────────────────────

func main() {
	initDB()
	seedData()
	startDailyReset()

	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		io.WriteString(w, "心动双人岛 OK\n")
	})

	// static uploads
	mux.Handle("/uploads/", http.StripPrefix("/uploads/", http.FileServer(http.Dir(uploadDir))))

	// auth
	mux.HandleFunc("/api/login", handleLogin)

	// settings
	mux.HandleFunc("/api/settings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" || r.Method == "PUT" {
			handleUpdateSettings(w, r)
		} else {
			handleGetSettings(w, r)
		}
	})

	// users
	mux.HandleFunc("/api/users", handleGetUsers)
	mux.HandleFunc("/api/user/update", handleUpdateUser)

	// status
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" || r.Method == "PUT" {
			handleUpdateStatus(w, r)
		} else {
			handleGetStatus(w, r)
		}
	})
	mux.HandleFunc("/api/status/add", handleAddStatus)
	mux.HandleFunc("/api/status/delete", handleDeleteStatus)

	// buffs
	mux.HandleFunc("/api/buffs", handleGetBuffs)
	mux.HandleFunc("/api/buff/toggle", handleToggleBuff)
	mux.HandleFunc("/api/buff/add", handleAddBuff)

	// anniversaries
	mux.HandleFunc("/api/anniversaries", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handleCreateAnniversary(w, r)
		} else {
			handleGetAnniversaries(w, r)
		}
	})
	mux.HandleFunc("/api/anniversary/delete", handleDeleteAnniversary)
	mux.HandleFunc("/api/anniversary/pin", handlePinAnniversary)

	// messages
	mux.HandleFunc("/api/messages", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handlePostMessage(w, r)
		} else {
			handleGetMessages(w, r)
		}
	})
	mux.HandleFunc("/api/message/read", handleMarkRead)

	// popup
	mux.HandleFunc("/api/popup", handleSendPopup)

	// tasks
	mux.HandleFunc("/api/tasks", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handleCreateTask(w, r)
		} else {
			handleGetTasks(w, r)
		}
	})
	mux.HandleFunc("/api/task/checkin", handleCheckIn)
	mux.HandleFunc("/api/task/confirm", handleConfirmTask)
	mux.HandleFunc("/api/checkins", handleGetCheckins)

	// trips
	mux.HandleFunc("/api/trips", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handleCreateTrip(w, r)
		} else {
			handleGetTrips(w, r)
		}
	})

	// packages
	mux.HandleFunc("/api/packages", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handleCreatePackage(w, r)
		} else {
			handleGetPackages(w, r)
		}
	})
	mux.HandleFunc("/api/package/pick", handlePickPackage)
	mux.HandleFunc("/api/packages/clear", handleClearPackages)

	// device status
	mux.HandleFunc("/api/device/status", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			handleUpdateDeviceStatus(w, r)
		} else {
			handleGetDeviceStatus(w, r)
		}
	})

	// privacy
	mux.HandleFunc("/api/privacy", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" || r.Method == "PUT" {
			handleUpdatePrivacy(w, r)
		} else {
			handleGetPrivacy(w, r)
		}
	})

	// upload
	mux.HandleFunc("/api/upload", handleUpload)
	mux.HandleFunc("/api/backup", handleBackup)
	mux.HandleFunc("/api/restore", handleRestore)

	// websocket
	mux.HandleFunc("/ws", handleWS)

	certFile := filepath.Join("tls", "island.crt")
	keyFile := filepath.Join("tls", "island.key")
	if _, err := os.Stat(certFile); err == nil {
		go func() {
			log.Printf("Island HTTPS starting on :443")
			if err := http.ListenAndServeTLS(":443", certFile, keyFile, mux); err != nil {
				log.Printf("HTTPS failed: %v", err)
			}
		}()
	}

	log.Printf("Island server starting on %s\n", listenAddr)
	if err := http.ListenAndServe(listenAddr, mux); err != nil {
		log.Fatal(err)
	}
}
