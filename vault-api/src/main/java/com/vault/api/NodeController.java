package com.vault.api;

import com.vault.api.dto.NodeResponse;
import com.vault.service.NodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {

    private final NodeService nodes;

    public NodeController(NodeService nodes) {
        this.nodes = nodes;
    }

    @GetMapping
    public List<NodeResponse> list() {
        return nodes.all().stream()
                .sorted(Comparator.comparing(n -> n.getNodeId()))
                .map(NodeResponse::of)
                .toList();
    }
}
