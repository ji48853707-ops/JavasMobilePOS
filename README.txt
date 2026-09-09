JAVAS FISHING 모바일 POS - Android 네이티브 핵심판

핵심 동작
1) 상품등록에서 카메라 바코드/QR 스캔
2) 코드 자동입력 → 상품명/판매가/재고 저장
3) 계산에서 같은 코드 스캔
4) 등록상품이면 즉시 삑 소리 + 진동 + 장바구니 자동추가 + 합계 자동계산
5) 카메라 연속 스캔, 같은 상품 반복 스캔 시 수량 증가
6) 개별 취소 / 마지막 취소 / 전체 취소
7) 판매완료 시 재고 차감, 누적 매출 저장

스캔 엔진
- Android CameraX
- Google ML Kit Barcode Scanning
- EAN-13 / EAN-8 / UPC-A / UPC-E / Code128 / Code39 / ITF / Codabar / QR
- 인식 성공 시 ToneGenerator 삑 소리 + 진동
- 0.9초 동일 코드 중복 방지

주의
이 폴더는 Android Studio에서 빌드할 소스 프로젝트입니다.
현재 실행 환경에는 Android SDK/Gradle이 없어 여기서 APK를 직접 빌드/서명하지 못했습니다.
Android Studio에서 프로젝트를 열고 Gradle Sync 후 실제 Android 폰에 Run 하면 됩니다.
