package com.skylane.repository;

import com.skylane.entity.ConversationMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, Long> {
}
