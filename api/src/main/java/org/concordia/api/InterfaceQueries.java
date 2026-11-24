// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import java.util.*;
import static org.concordia.api.Interfaces.*;

public interface InterfaceQueries {
    long latest();
    void validate(long snapshot);
    Optional<Contract> find(Kind kind, long snapshot, String id);
    List<Contract> list(Kind kind, long snapshot, String after, int limit, String resolutionJson);
}
