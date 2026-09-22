package uno.acloud.team.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.entity.Team;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;
    private TeamEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new TeamEventPublisher(rabbitTemplate, objectMapper);
    }

    // ==================== publishTeamCreated — success on first attempt ====================

    @Test
    void publishTeamCreated_shouldSendToRabbitMQ() {
        Team team = new Team();
        team.setId(10L);
        team.setName("TestTeam");
        team.setAvatar("avatar.png");
        team.setDescription("desc");
        team.setOwnerUserId(1L);

        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");
        owner.setName("Admin");
        owner.setEmail("admin@test.com");

        CreateTeamRequest request = new CreateTeamRequest();

        publisher.publishTeamCreated(team, owner, request);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED),
                anyString());
    }

    // ==================== publishMemberRemoved — success ====================

    @Test
    void publishMemberRemoved_shouldSendToRabbitMQ() {
        publisher.publishMemberRemoved(10L, 2L);

        // 成员事件使用普通 3 参 convertAndSend：不再挂 MessagePostProcessor 设置消息优先级。
        // 原因见 TeamEventPublisher 类注释 —— 全仓无队列声明 x-max-priority，setPriority 静默无效。
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED),
                anyString());
    }

    // ==================== Retry: fail twice, succeed third time ====================

    @Test
    void publish_shouldRetryOnFailureAndSucceedOnThirdAttempt() {
        Team team = new Team();
        team.setId(10L);
        team.setName("TestTeam");
        team.setOwnerUserId(1L);

        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");

        CreateTeamRequest request = new CreateTeamRequest();

        // Fail twice, succeed on third — use doThrow/doNothing for void method
        doThrow(new AmqpException("Connection lost"))
                .doThrow(new AmqpException("Connection lost"))
                .doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        publisher.publishTeamCreated(team, owner, request);

        // Verify 3 attempts were made
        verify(rabbitTemplate, times(3)).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED),
                anyString());
    }

    // ==================== Retry: all 3 attempts fail ====================

    @Test
    void publish_allRetriesFail_shouldThrowBusinessException() {
        Team team = new Team();
        team.setId(10L);
        team.setName("TestTeam");
        team.setOwnerUserId(1L);

        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(1L);
        owner.setUsername("admin");

        CreateTeamRequest request = new CreateTeamRequest();

        // All 3 attempts fail
        doThrow(new AmqpException("Connection lost"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        // Publisher throws BusinessException after max retries
        assertThrows(BusinessException.class,
                () -> publisher.publishTeamCreated(team, owner, request));

        // Verify exactly 3 attempts were made
        verify(rabbitTemplate, times(3)).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED),
                anyString());
    }

    // ==================== publishMemberCreated — success ====================

    @Test
    void publishMemberCreated_shouldSendCorrectRoutingKey() {
        UserInfoDTO user = new UserInfoDTO();
        user.setId(2L);
        user.setUsername("newuser");
        user.setName("New User");
        user.setEmail("new@test.com");
        user.setAvatar("avatar.png");

        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setRoleCode("team_member");

        publisher.publishMemberCreated(10L, user, request);

        // 同 publishMemberRemoved：成员事件走普通 3 参 convertAndSend，不设消息优先级。
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED),
                anyString());
    }

    // ==================== Retry: member removed fails twice then succeeds ====================

    @Test
    void publishMemberRemoved_shouldRetryOnFailure() {
        doThrow(new AmqpException("Timeout"))
                .doThrow(new AmqpException("Timeout"))
                .doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        publisher.publishMemberRemoved(10L, 2L);

        verify(rabbitTemplate, times(3)).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED),
                anyString());
    }

    // ==================== 真正的顺序机制：sequenceNumber 单调递增 ====================

    /**
     * 成员事件的先后顺序<b>不靠 MQ 消息优先级</b>（全仓无队列声明 {@code x-max-priority}，
     * {@code setPriority} 在未声明的队列上静默无效），而是靠事件体内的 {@code sequenceNumber}：
     * 消费端据此检测乱序。本测试把该机制钉住 —— 一旦有人把它换成"优先级"之类的
     * 无效手段，或者去掉 sequenceNumber，这里就会变红。
     */
    @Test
    void memberEvents_carryMonotonicallyIncreasingSequenceNumber_forOrdering() throws Exception {
        UserInfoDTO user = new UserInfoDTO();
        user.setId(2L);
        user.setUsername("newuser");
        user.setName("New User");
        user.setEmail("new@test.com");
        user.setAvatar("avatar.png");
        CreateTeamMemberRequest request = new CreateTeamMemberRequest();
        request.setRoleCode("team_member");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        publisher.publishMemberCreated(10L, user, request);
        publisher.publishMemberRemoved(10L, 2L);

        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE), anyString(), payload.capture());

        List<String> bodies = payload.getAllValues();
        long addedSeq = objectMapper.readTree(bodies.get(0)).get("sequenceNumber").asLong();
        long removedSeq = objectMapper.readTree(bodies.get(1)).get("sequenceNumber").asLong();

        assertTrue(addedSeq > 0L,
                "成员事件必须携带非零 sequenceNumber，否则消费端无法检测乱序；实际=" + addedSeq);
        assertTrue(removedSeq > addedSeq,
                "后发布的 removed 事件 sequenceNumber 必须大于先发布的 added（"
                        + addedSeq + " -> " + removedSeq + "），这是顺序保证的唯一依据");
    }
}
