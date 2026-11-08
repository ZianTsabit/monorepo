package com.ziantsabit.api.dto;

import com.ziantsabit.api.validation.Required;


/**
 * A page's new document. Only {@code data} is writable -- a writable key would
 * let a write turn the CV into the About page -- and its shape is checked and
 * normalised by PageDocuments.
 */
public record PageRequest(@Required Object data) {
}
