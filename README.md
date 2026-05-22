[English](#english) | [日本語](#japanese)

---

<a id="english"></a>

# SimpleAuth

A simple authentication plugin for Minecraft.  
Players who fail to enter the password within a set number of seconds after joining will be kicked.  
Players who have authenticated once will be automatically logged in on future joins.

Compatible with: **Paper 1.21** (Minecraft Java 1.21)

## Features

- 30-second countdown starts when a player joins (configurable)
- While unauthenticated, the following actions are blocked: movement, chat, other commands, block place/break, interactions, and damage
- Two authentication methods:
  - **Type the password directly in chat** (recommended — chat from unauthenticated players is not visible to others)
  - **`/auth <password>` command**
- **Auto-login**: Players who have authenticated once are automatically logged in on future joins (identified by UUID)
- The password is never displayed in any server message (prevents leaks)
- Auto-kick on timeout
- `/authreload` to reload configuration (OP only)
- `/authforget` to remove your own auto-login registration
- In-game message language is configurable (`en` / `ja`)

## Build

```
mvn package
```

## Installation

1. Copy the built `SimpleAuth.jar` to your server's `plugins\` folder
2. Start the server (`plugins\SimpleAuth\config.yml` will be generated automatically)
3. Edit `config.yml` to set your password, timeout, and language
4. Use `/authreload` to apply changes (or restart the server)

## Configuration (config.yml)

```yaml
password: "password"      # Authentication password
timeout-seconds: 30       # Time limit for authentication (seconds)
remember-players: true    # true: auto-login after first auth / false: require password every time
language: "en"            # In-game message language: en (English) / ja (Japanese)
```

## Commands

| Command | Description | Permission |
|---|---|---|
| `/auth <password>` | Authenticate | Everyone |
| `/authforget` | Remove your auto-login registration | Everyone |
| `/authreload` | Reload configuration | OP only |

## Auto-login Details

- The UUID of authenticated players is saved to `plugins/SimpleAuth/authed_players.yml`
- Players whose UUID matches on the next join are logged in instantly without a password
- Auto-login persists across server restarts
- Use `/authforget` to remove your registration (e.g., on a shared PC)

---

<a id="japanese"></a>

# SimpleAuth

Minecraft 用のシンプルな認証プラグイン。  
参加してから決められた秒数以内にパスワードを入力しないとキックします。  
一度認証したプレイヤーは次回以降パスワード不要で自動ログインします。

対応: **Paper 1.21** (Minecraft Java 1.21)

## 機能

- 参加すると 30 秒のカウントダウン開始（変更可）
- 認証していない間は：移動・チャット・他コマンド・ブロック設置/破壊・インタラクト・ダメージ受け取りを全て無効化
- 2つの認証方法：
  - **チャットでパスワードを直接入力**（推奨。未認証のチャットは公開されないので安全）
  - **`/auth <パスワード>` コマンド**
- **自動ログイン**：一度認証済みのプレイヤーは次回以降パスワード入力なし（UUID で識別）
- パスワードはサーバーが送るメッセージには一切表示されません（漏洩防止）
- 時間切れで自動キック
- `/authreload` で設定再読み込み（OP のみ）
- `/authforget` で自分の自動ログイン登録を解除
- ゲーム内メッセージの言語を設定で切り替え可能（`en` / `ja`）

## ビルド

```
mvn package
```

## インストール

1. ビルドした `SimpleAuth.jar` をサーバーの `plugins\` フォルダにコピー
2. サーバーを起動（`plugins\SimpleAuth\config.yml` が自動生成）
3. パスワード・秒数・言語を変えたい場合は `config.yml` を編集
4. `/authreload` で反映（または再起動）

## 設定 (config.yml)

```yaml
password: "password"      # 認証パスワード
timeout-seconds: 30       # 認証猶予時間（秒）
remember-players: true    # true: 一度認証したら次回以降自動ログイン / false: 毎回パスワード必要
language: "en"            # ゲーム内メッセージの言語: en (英語) / ja (日本語)
```

## コマンド

| コマンド | 説明 | 権限 |
|---|---|---|
| `/auth <パスワード>` | 認証する | 全員 |
| `/authforget` | 自分の自動ログイン登録を解除する | 全員 |
| `/authreload` | 設定を再読み込みする | OP のみ |

## 自動ログインの仕組み

- 認証に成功したプレイヤーの UUID を `plugins/SimpleAuth/authed_players.yml` に保存します
- 次回参加時に UUID が一致すれば、パスワードなしで即座にログインします
- サーバー再起動後も自動ログインは維持されます
- `/authforget` コマンドで自分の登録を解除できます（例：共用 PC での利用時など）
