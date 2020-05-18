# ImageSimilarityPlugin
本项目主要参考[StaySense/fast-cosine-similarity](https://github.com/StaySense/fast-cosine-similarity)

通过Python程序向ES集群写入100w条数据，要注意索引的mappings设置，feature是ES存储特性向量数据的字段，以base64形式存储。不同编程语言List & base64转换程序见[StaySense/fast-cosine-similarity](https://github.com/StaySense/fast-cosine-similarity)

```python
import random
import base64
import numpy as np
from elasticsearch import Elasticsearch, helpers

dbig = np.dtype('>f8')

es = Elasticsearch()

body = {
    "mappings": {
        "image_search": {
            "properties": {
                "id": {
                    "type": "keyword"
                },
                "feature": {
                    "type": "binary",
                    "doc_values": True
                }
            }
        }
    }
}

index = 'test'
es.indices.delete(index=index, ignore=404)
es.indices.create(index=index, ignore=400, body=body)


def decode_float_list(base64_string):
    """
    base64 转 list
    :param base64_string:
    :return:
    """
    bytes_ = base64.b64decode(base64_string)
    return np.frombuffer(bytes_, dtype=dbig).tolist()


def encode_array(arr):
    """
    List 转 base64
    :param arr:
    :return:
    """
    base64_str = base64.b64encode(np.array(arr).astype(dbig)).decode("utf-8")
    return base64_str


def generator():
    i = 0
    while True:
        yield {

                'id': i,
                'feature': encode_array([random.random(), random.random()])
            }
        i += 1
        if i >= 1000000:
            break


# 批量插入100w数据到es
helpers.bulk(es, generator(), index=index, doc_type='image_search')

```

查询程序，注意source和lang要和插件里一致。
```python
import time
import json
import base64
import numpy as np
from elasticsearch import Elasticsearch, helpers

dbig = np.dtype('>f8')

es = Elasticsearch()

body = {
    "from": 0,
    "size": 5,
    "_source": {
        "excludes": ""
    },
    "sort": {
        "_score": {
            "order": "asc"
        }
    },
    "query": {
        "function_score": {
            "query": {
                "match_all": {}
            },
            "functions": [
                {
                    "script_score": {
                        "script": {
                            "source": "DebugWorld",
                            "lang": "ImageSimilarity",
                            "params": {
                                "field": "feature",
                                "feature": [0.01, 0.03]
                            }
                        }
                    }
                }
            ]
        }
    }
}


def decode_float_list(base64_string):
    """
    base64 转 list
    :param base64_string:
    :return:
    """
    bytes_ = base64.b64decode(base64_string)
    return np.frombuffer(bytes_, dtype=dbig).tolist()


time_list = list()
for i in range(1):
    start_time = time.time()
    result = es.search(index='test', doc_type='image_search', body=body)
    for hit in result['hits']['hits']:
        hit['_source']['feature'] = decode_float_list(hit['_source']['feature'])
    time_list.append(time.time() - start_time)
    print(json.dumps(result, indent=4))

print(sum(time_list)/len(time_list), max(time_list), min(time_list))
```