# JAVAS FISHING 모바일 POS

Android 네이티브 스캐너 핵심판.

- CameraX 후면카메라 실시간 프리뷰
- Google ML Kit 바코드/QR 인식
- 인식 성공 즉시 삑 소리 + 짧은 진동
- 상품등록 → 계산 스캔 → 장바구니 자동추가 → 합계
- 동일 상품 재스캔 시 수량 증가
- 개별취소 / 마지막취소 / 전체취소
- 판매완료 시 재고 차감 / 누적매출 저장

GitHub Actions는 `main` 브랜치에 올라오면 자동으로 debug APK를 생성합니다.
