package com.ziantsabit.api.dto;

import com.ziantsabit.api.validation.Required;


public record LoginRequest(
        @Required String username,
        @Required String password) {
}
