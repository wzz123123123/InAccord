package com.inforvans.accord.controlplane.http;

import jakarta.servlet.Filter;

/**
 * Installs an identity already verified by an upstream enterprise authentication boundary.
 * No adapter is provided by default, so the API remains closed until one is configured.
 */
public interface FoundationIdentityAdapter extends Filter {}
