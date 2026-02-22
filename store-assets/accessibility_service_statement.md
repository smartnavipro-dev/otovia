# Play Console — Accessibility Service 使用理由申請文

## 日本語

### アクセシビリティサービスの使用目的

Otoviaは、視覚障害・弱視・識字困難・運動障害など、視覚的な読書が困難なユーザーのために設計されたアクセシビリティツールです。

アクセシビリティサービスは、読み上げ中の自動ページめくりのためにのみ使用します。

### なぜアクセシビリティサービスが必要か

電子書籍の読み上げ中にページを自動でめくるには、ジェスチャー操作（スワイプ）を実行する必要があります。Android標準APIでは他のアプリに対してジェスチャーを送信することができません。アクセシビリティサービスの `performGesture` APIのみがこれを実現できます。

ユーザーが手を使えない状況（運転中・料理中・手の障害等）でも読書を継続できるよう、自動ページめくりは本アプリの中核機能です。

### 実際に行う操作

- ページめくりのスワイプジェスチャーのみを実行します
- 他のアプリのコンテンツを読み取ることはありません
- キーボード入力の傍受は行いません
- 特定のアプリを標的にせず、すべての電子書籍アプリに対応します

---

## English (for Play Console form)

### Why does this app use Accessibility Service?

Otovia is an accessibility tool designed for users who have difficulty with visual reading, including those with visual impairments, low vision, dyslexia, and motor disabilities.

The Accessibility Service is used **solely** for automatic page turning during text-to-speech playback.

### Why can't this be done without Accessibility Service?

To automatically turn pages in e-book apps while reading aloud, the app must perform a swipe gesture on another app. The standard Android API does not allow sending gestures to other applications. Only the Accessibility Service `performGesture` API makes this possible.

Automatic page turning is a core feature that allows users who cannot use their hands (while driving, cooking, or due to motor disabilities) to continue reading.

### Exact actions performed

- Performs only page-turning swipe gestures
- Does NOT read content from other apps
- Does NOT intercept keyboard input
- Does NOT target any specific app — works with all e-book applications

### Accessibility benefit

This feature directly serves users who:
- Cannot hold and interact with their phone continuously
- Have visual impairments and rely on audio
- Have motor disabilities that make manual page-turning difficult
- Use the app while their hands are occupied (a form of situational disability)

The Accessibility Service enables truly hands-free reading for all these users.
