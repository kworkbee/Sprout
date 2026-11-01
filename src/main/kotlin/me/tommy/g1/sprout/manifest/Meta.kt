package me.tommy.g1.sprout.manifest

import io.fabric8.kubernetes.api.model.ObjectMeta
import me.tommy.g1.sprout.api.PreviewEnvironment
import me.tommy.g1.sprout.domain.managedLabels
import me.tommy.g1.sprout.domain.namespaceName

internal fun PreviewEnvironment.clusterMeta(resourceName: String) =
    ObjectMeta().apply {
        name = resourceName
        labels = managedLabels
    }

internal fun PreviewEnvironment.childMeta(resourceName: String) =
    clusterMeta(resourceName).apply { namespace = namespaceName }
