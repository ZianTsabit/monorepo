package com.ziantsabit.api.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Hidden;

/** {@code GET /api/}: a map of the resources, as DRF's router index was. */
@RestController
@Hidden
public class ApiRootController {

    @GetMapping("/api")
    public Map<String, String> root() {
        Map<String, String> resources = new LinkedHashMap<>();
        for (String name : new String[] {"posts", "books", "comments", "pages"}) {
            resources.put(name, ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/" + name + "/").build().toUriString());
        }
        return resources;
    }
}
