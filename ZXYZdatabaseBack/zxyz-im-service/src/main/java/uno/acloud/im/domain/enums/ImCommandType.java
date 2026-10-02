package uno.acloud.im.domain.enums;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * WebSocket 入站命令类型白名单。
 *
 * <p><b>为什么用枚举而不是字符串常量比对</b>：此前 {@code ImCommandDispatcher} 用
 * {@code "SEND_TEXT".equals(request.type())} 两段 if-else 派发，未知类型时把<b>用户可控</b>的
 * {@code type} 原样拼进 {@code BusinessException} 消息，经 GlobalExceptionHandler → Result
 * 下发回 WS 客户端（可据此探测服务端支持面、注入不可信文本）。改成枚举后：
 * ① 支持面是一份可枚举的白名单，新增命令改这里一处；
 * ② 未知类型统一落到同一条不含外部输入的文案上。</p>
 *
 * <p>枚举常量名即 wire 协议里的 {@code type} 字面量（与前端 switch 的分支名一一对应），
 * 不要为了「好看」改常量名——那会静默改变协议。</p>
 */
public enum ImCommandType {

    SEND_TEXT,
    SEND_FILE_CARD;

    /** wire 名 → 枚举；构建期一次，避免每条消息都 values() 线性扫描。 */
    private static final Map<String, ImCommandType> BY_WIRE_NAME =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(Enum::name, type -> type));

    /**
     * 按协议字面量解析命令类型。
     *
     * @param wireName 客户端下发的 {@code type}（可空、可控）
     * @return 命中白名单时返回枚举；未知/空/大小写不符时返回 {@link Optional#empty()}
     */
    public static Optional<ImCommandType> fromWireName(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }
}
