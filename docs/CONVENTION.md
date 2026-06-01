# 코드 컨벤션

## 레이어 구조 및 데이터 흐름

```
HTTP Request
    │
    ▼
[Controller]
  - @Valid 로 Request 검증
  - Request.toCommand() 변환 후 Facade 또는 Service 호출
  - 반환 DTO → Response 변환
  - ApiResponse<T> 로 감싸서 반환
    │
    ▼
[Facade]  ← 여러 Service를 조합하는 경우에만 사용 (인터페이스 없음)
  - 입력: XxxCommand
  - 출력: XxxDto
  - 도메인 서비스 간 조합·위임만 담당
  - 직접 Repository 참조 금지
    │
    ▼
[Service]
  - 입력: XxxCommand
  - 출력: XxxDto
  - 단일 도메인 비즈니스 로직 담당
  - 자신의 Repository만 참조
  - Entity를 직접 Controller/Facade에 노출하지 않음
    │
    ▼
[Repository]
  - Entity 단위로만 다룸
  - DTO 반환 금지
    │
    ▼
[DB / Redis]
```

**Facade vs Service 구분 기준:**
- 다른 Service를 주입받아 조합 → **Facade** (인터페이스 없음, `{Domain}Facade`)
- 자신의 Repository만 사용 → **Service** (인터페이스 + Impl)

---

## 네이밍 규칙

### 클래스명

| 레이어 | 패턴 | 예시 |
|--------|------|------|
| Controller | `{Domain}Controller` | `ProductController` |
| Facade | `{Domain}Facade` | `CheckoutFacade` |
| Service (인터페이스) | `{Domain}Service` | `ProductService` |
| Service (구현체) | `{Domain}ServiceImpl` | `ProductServiceImpl` |
| Repository | `{Domain}Repository` | `ProductRepository` |
| Entity | `{Domain}` | `Product` |
| Request | `{Action}{Domain}Request` | `CreateProductRequest` |
| Response | `{Action}{Domain}Response` | `CreateProductResponse` |
| Command | `{Action}{Domain}Command` | `CreateProductCommand` |
| DTO | `{Domain}Dto` | `ProductDto` |
| Exception | `{Domain}{Reason}Exception` | `ProductNotFoundException` |

- `Action` 접두사는 **쓰기 작업(Create / Update / Delete)에만** 붙인다.
- 단순 조회(GET)는 접두사 없이 `{Domain}Request` / `{Domain}Response` / `{Domain}Command`. 예: `CheckoutResponse`, `CheckoutCommand` (`GetCheckout*` 사용 금지)

### 메서드명

| 동작 | 패턴 | 예시 |
|------|------|------|
| 단건 조회 | `get{Domain}` | `getProduct` |
| 목록 조회 | `get{Domain}List` | `getProductList` |
| 생성 | `create{Domain}` | `createProduct` |
| 수정 | `update{Domain}` | `updateProduct` |
| 삭제 | `delete{Domain}` | `deleteProduct` |

---

## 클래스 설계 규칙

### Entity
```java
// 일반 class (JPA 프록시 생성을 위해 record/final 사용 금지)
// BaseEntity 상속으로 공통 필드 처리
@Entity
@Getter
@Table(name = "products")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    protected Product() {
    }

    public Product(String name) {
        this.name = name;
    }

    // 비즈니스 메서드
    public void updateName(String name) {
        this.name = name;
    }
}
```

- 기본(no-arg) 생성자는 JPA용으로 `protected`
- getter는 Lombok `@Getter`로 생성 (클래스 레벨)
- setter 직접 노출 금지 → 비즈니스 메서드로만 상태 변경
- 엔티티에 `record` / Lombok `@Data` / `@Setter` 사용 금지 (equals/hashCode + 프록시 충돌, 캡슐화 위반)

### Command / DTO
```java
// record 사용
public record CreateProductCommand(
        String name,
        long price,
        int stock,
        long categoryId
) {
}

public record ProductDto(
        Long id,
        String name,
        long price,
        int stock
) {
    public static ProductDto from(Product product) {
        return new ProductDto(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStock()
        );
    }
}
```

### Request / Response
```java
public record CreateProductRequest(
        @NotBlank String name,
        @Min(0) long price,
        @Min(0) int stock,
        long categoryId
) {
    public CreateProductCommand toCommand() {
        return new CreateProductCommand(name, price, stock, categoryId);
    }
}

public record CreateProductResponse(
        Long id,
        String name
) {
    public static CreateProductResponse from(ProductDto dto) {
        return new CreateProductResponse(dto.id(), dto.name());
    }
}
```

- Bean Validation 어노테이션은 record 컴포넌트에 직접 부착 (`@NotBlank`, `@Min` 등)
- `toCommand()` : Request → Command 변환 책임은 Request가 가짐
- `from(dto)` : Response 생성 책임은 Response가 가짐

---

## 공통 응답 포맷

```java
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final ErrorResponse error;

    private ApiResponse(boolean success, T data, ErrorResponse error) {
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> fail(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }

    public boolean isSuccess() {
        return success;
    }

    public T getData() {
        return data;
    }

    public ErrorResponse getError() {
        return error;
    }
}

public record ErrorResponse(
        String code,
        String message
) {
}
```

### 성공 응답 예시
```json
{
  "success": true,
  "data": { "id": 1, "name": "상품명" }
}
```

### 실패 응답 예시
```json
{
  "success": false,
  "error": {
    "code": "PRODUCT_NOT_FOUND",
    "message": "상품을 찾을 수 없습니다."
  }
}
```

---

## 예외 처리

### ErrorCode
```java
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력입니다."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),

    // Product
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다."),
    OUT_OF_STOCK(HttpStatus.BAD_REQUEST, "재고가 부족합니다."),

    // Cart
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니를 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다."),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_CANNOT_CANCEL(HttpStatus.BAD_REQUEST, "취소할 수 없는 주문 상태입니다."),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 일치하지 않습니다."),
    PAYMENT_CONFIRM_FAILED(HttpStatus.BAD_GATEWAY, "결제 승인에 실패했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
```

### 커스텀 예외
```java
public class BaseException extends RuntimeException {

    private final ErrorCode errorCode;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

public class ProductNotFoundException extends BaseException {
    public ProductNotFoundException() {
        super(ErrorCode.PRODUCT_NOT_FOUND);
    }
}

public class OutOfStockException extends BaseException {
    public OutOfStockException() {
        super(ErrorCode.OUT_OF_STOCK);
    }
}
// ...
```

### GlobalExceptionHandler
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ApiResponse<Void>> handleBaseException(BaseException e) {
        return ResponseEntity
                .status(e.getErrorCode().getStatus())
                .body(ApiResponse.fail(new ErrorResponse(e.getErrorCode().name(), e.getErrorCode().getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(it -> it.getField() + ": " + it.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.fail(new ErrorResponse(ErrorCode.INVALID_INPUT.name(), message)));
    }
}
```

---

## StockStore 인터페이스

```java
public interface StockStore {
    boolean decrease(Long productId, int quantity);  // false = 재고 부족
    void increase(Long productId, int quantity);      // 롤백 또는 취소 시
    long getStock(Long productId);
    void initialize(Long productId, long stock);      // DB → Store 동기화
}
```

### 구현체 전환
`application.yml` 설정 하나로 스위칭:

```yaml
stock:
  store: redis   # redis | local
```

```java
// Redis 구현체
@Component
@ConditionalOnProperty(name = "stock.store", havingValue = "redis")
public class RedisStockStore implements StockStore {
    private final RedisTemplate<String, Long> redisTemplate;
    // ...
}

// Map 구현체 (Redis 없이 개발/테스트)
@Component
@ConditionalOnProperty(name = "stock.store", havingValue = "local", matchIfMissing = true)
public class LocalStockStore implements StockStore {
    // ...
}
```

---

## 패키지 구조 상세

```
com.example.booking
├── controller/                          ← HTTP 진입점 전용
│   └── {도메인}/
│       ├── {Domain}Controller.java
│       ├── request/
│       │   ├── Create{Domain}Request.java
│       │   └── Update{Domain}Request.java
│       └── response/
│           └── {Domain}Response.java    (쓰기는 {Action}{Domain}Response)
│
├── {도메인}/                             ← 도메인 패키지 (domain/ 래퍼 없음)
│   ├── {Domain}Facade.java              (선택 — 여러 서비스 조합 시에만)
│   ├── command/                         (Facade 입력 Command)
│   │   └── {Domain}Command.java
│   ├── service/
│   │   ├── {Domain}Service.java         (인터페이스)
│   │   ├── {Domain}ServiceImpl.java     (구현체)
│   │   └── command/                     (Service 입력 Command)
│   │       ├── Create{Domain}Command.java
│   │       └── Update{Domain}Command.java
│   ├── repository/
│   │   └── {Domain}Repository.java
│   ├── entity/
│   │   └── {Domain}.java
│   └── dto/
│       └── {Domain}Dto.java
│
└── common/                              ← 공통
    ├── config/
    ├── entity/
    ├── exception/
    └── response/
```

레이어별 소유:
- **Request / Response** → `controller/{도메인}/` 소유 (HTTP 형상)
- **Command** → `{도메인}/service/command/` 소유 (비즈니스 형상)
- **Dto** → `{도메인}/dto/` (서비스↔컨트롤러 레이어 간 공유)
- **Entity** → `{도메인}/entity/` (JPA 영속 모델)

---

## 기타 규칙

- `TODO` 주석 사용 금지 → 미완성 코드는 커밋하지 않음
- 매직 넘버 사용 금지 → `private static final` 상수로 정의
- `null` 단정 대신 `Optional` 또는 명시적 null 체크 → `orElseThrow(XxxException::new)` 으로 대체
- DB 페이징은 `Pageable` 사용, 기본 페이지 사이즈 20
- 금액은 `long` (원 단위), `double` / `float` 사용 금지. DB 컬럼은 돈 = `BIGINT`(`long`) / 수량 = `INT`(`int`)로 매핑
- 날짜/시간 필드는 기본적으로 `ZonedDateTime` 사용 (`LocalDateTime` 금지) — 이벤트 오픈 시각·결제 시각 등 시점이 타임존에 의존하므로 오프셋을 보존한다
- **예외 — 달력 날짜 / 숙소 벽시계 시각**: 체크인·체크아웃처럼 타임존 변환이 일어나면 안 되는 필드는 `LocalDate` / `LocalTime` 사용
  - `checkInDate`, `checkOutDate` → `LocalDate` (Agoda·Expedia·Booking.com 모두 `YYYY-MM-DD`)
  - `checkInTime`, `checkOutTime` → `LocalTime` (숙소 고정 정책값, 타임존 무관)
  - JSON 직렬화: `"checkInDate": "2024-06-12"`, `"checkInTime": "15:00:00"`
