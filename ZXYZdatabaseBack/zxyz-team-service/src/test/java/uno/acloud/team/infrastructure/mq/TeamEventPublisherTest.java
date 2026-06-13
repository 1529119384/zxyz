package uno.acloud.team.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

        // m52: member events now use publishWithPriority (4-arg convertAndSend with MessagePostProcessor)
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED),
                anyString(),
                any(org.springframework.amqp.core.MessagePostProcessor.class));
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

        // m52: member events now use publishWithPriority (4-arg convertAndSend with MessagePostProcessor)
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED),
                anyString(),
                any(org.springframework.amqp.core.MessagePostProcessor.class));
    }

    // ==================== Retry: member removed fails twice then succeeds ====================

    @Test
    void publishMemberRemoved_shouldRetryOnFailure() {
        // m52: member events use 4-arg convertAndSend with MessagePostProcessor
        doThrow(new AmqpException("Timeout"))
                .doThrow(new AmqpException("Timeout"))
                .doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(), any(org.springframework.amqp.core.MessagePostProcessor.class));

        publisher.publishMemberRemoved(10L, 2L);

        verify(rabbitTemplate, times(3)).convertAndSend(
                eq(RabbitMqConstants.EXCHANGE),
                eq(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED),
                anyString(),
                any(org.springframework.amqp.core.MessagePostProcessor.class));
    }
}
