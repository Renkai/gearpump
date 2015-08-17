/*
 * Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

angular.module('dashboard')

/** TODO: to be absorbed as scalajs */
  .factory('models', ['restapi', 'locator', 'StreamingDag', 'Metrics',
    function(restapi, locator, StreamingDag, Metrics) {
      'use strict';

      var util = {
        extractLocation: function(actorPath) {
          return actorPath
            .split('@')[1]
            .split('/')[0];
        },
        usage: function(current, total) {
          return total > 0 ? 100 * current / total : 0;
        }
      };

      /**
       * Retrieves a model from backend as a promise. The resolved object will have a $subscribe method, which can be
       * used to watch model changes within a scope. */
      function get(path, decodeFn, subscribePath) {
        return restapi.get(path).then(function(response) {
          var oldModel;
          var model = decodeFn(response.data);

          model.$subscribe = function(scope, onData) {
            restapi.subscribe(subscribePath || path, scope, function(data) {
              var newModel = decodeFn(data);
              if (!_.isEqual(newModel, oldModel)) {
                onData(newModel);
                oldModel = newModel;
              }
            });
          };

          model.$data = function() {
            var result = {};
            _.forEach(model, function(value, key) {
              if (!angular.isFunction(value)) {
                result[key] = value;
              }
            });
            return result;
          };

          return model;
        });
      }

      var decoder = {
        _asAssociativeArray: function(objs, decodeFn, keyName) {
          var result = {};
          _.map(objs, function(obj) {
            var model = decodeFn(obj);
            var key = model[keyName];
            result[key] = model;
          });
          return result;
        },
        /** Do the necessary deserialization. */
        master: function(wrapper) {
          var obj = wrapper.masterDescription;
          angular.merge(obj, {
            leader: obj.leader.join(':'),
            // missing properties
            isHealthy: obj.masterStatus === 'synced',
            // operations
            configLink: restapi.masterConfigLink()
          });
          // upickle crap
          obj.cluster = obj.cluster.map(function(tuple) {
            return tuple.join(':');
          });
          return obj;
        },
        workers: function(objs) {
          return decoder._asAssociativeArray(objs, decoder.worker.detail, 'workerId');
        },
        worker: {
          detail: function(obj) {
            var slotsUsed = obj.totalSlots - obj.availableSlots;
            return angular.merge(obj, {
              // missing properties
              location: util.extractLocation(obj.actorPath),
              isHealthy: obj.state === 'active',
              slots: {
                usage: util.usage(slotsUsed, obj.totalSlots),
                used: slotsUsed,
                total: obj.totalSlots
              },
              // operations
              pageUrl: locator.worker(obj.workerId),
              configLink: restapi.workerConfigLink(obj.workerId)
            });
          },
          metrics: function(wrapper) {
            var result = {};
            _.map(wrapper.metrics, function(metric) {
              // String before first '.' is `'worker' + workerId`
              var metricName = metric.value.name.split('.').slice(1).join('.');
              if (!result.hasOwnProperty(metricName)) {
                result[metricName] = [];
              }
              result[metricName].push({
                time: Number(metric.time),
                value: Number(metric.value.value)
              });
            });
            return result;
          }
        },
        apps: function(wrapper) {
          var objs = wrapper.appMasters;
          return decoder._asAssociativeArray(objs, decoder.app.summary, 'appId');
        },
        app: {
          summary: function(obj) {
            // todo: add `type` field to summary and detailed app response
            angular.merge(obj, {
              type: 'streaming'
            });

            return angular.merge(obj, {
              // missing properties
              isRunning: obj.status === 'active',
              location: util.extractLocation(obj.appMasterPath),
              // operations
              pageUrl: locator.app(obj.appId, obj.type),
              terminate: function() {
                return restapi.killApp(obj.appId);
              },
              restart: function() {
                return restapi.restartAppAsync(obj.appId);
              }
            });
          },
          detail: function(obj) {
            // todo: add `type` field to summary and detailed app response
            angular.merge(obj, {
              status: 'active',
              type: obj.hasOwnProperty('dag') ? 'streaming' : ''
            });

            angular.merge(obj, {
              // missing properties
              aliveFor: moment() - obj.startTime,
              isRunning: true, // todo: handle empty response, which is the case application is stopped
              // operations
              pageUrl: locator.app(obj.appId, obj.type),
              configLink: restapi.appConfigLink(obj.appId),
              restart: function() {
                console.log('currently still not possible, because app id will be changed after a restart.');
              },
              terminate: function() {
                return restapi.killApp(obj.appId);
              }
            });

            // upickle crap
            _.forEach(obj.executors, function(executor) {
              executor.isRunning = executor.status === 'active';
              executor.pageUrl = locator.executor(obj.appId, obj.type, executor.executorId);
              executor.workerPageUrl = locator.worker(executor.workerId);
              // todo: store backend status, so that we can query worker information efficient.
            });
            return obj;
          },
          metrics: function(wrapper) {
            return _.map(wrapper.metrics, function(obj) {
              switch (obj.value.$type) {
                case 'org.apache.gearpump.metrics.Metrics.Meter':
                  return Metrics.decode.meter(obj);
                case 'org.apache.gearpump.metrics.Metrics.Histogram':
                  return Metrics.decode.histogram(obj);
                case 'org.apache.gearpump.metrics.Metrics.Gauge':
                  return Metrics.decode.gauge(obj);
              }
            });
          },
          stallingProcessors: function(wrapper) {
            // TODO: I prefer this format: {processorId: [taskIndex1, taskIndex2,...]}
            return _.groupBy(wrapper.tasks, 'processorId');
          }
        }
      };

      return {
        $get: {
          master: function() {
            return get('/master',
              decoder.master);
          },
          workers: function() {
            return get('/master/workerlist',
              decoder.workers);
          },
          worker: {
            detail: function(workerId) {
              return get('/worker/' + workerId,
                decoder.worker.detail);
            },
            metrics: function(workerId, all) {
              var base = '/worker/' + workerId + '/metrics/worker' + workerId;
              var firstTimePath = base + (all ? '' : '?readLatest=true');
              var subscribePath = base + '?readLatest=true';
              return get(firstTimePath, decoder.worker.metrics, subscribePath);
            }
          },
          apps: function() {
            return get('/master/applist',
              decoder.apps);
          },
          app: {
            detail: function(appId) {
              return get('/appmaster/' + appId + '?detail=true',
                decoder.app.detail);
            },
            metrics: function(appId, all) {
              var base = '/appmaster/' + appId + '/metrics/app' + appId;
              var firstTimePath = base + (all ? '' : '?readLatest=true');
              var subscribePath = base + '?readLatest=true';
              return get(firstTimePath, decoder.app.metrics, subscribePath);
            },
            stallingProcessors: function(appId) {
              return get('/appmaster/' + appId + '/stallingtasks',
                decoder.app.stallingProcessors);
            }
          }
        },

        // TODO: scalajs should return a app.details object with dag, if it is a streaming application.
        createDag: function(appId, clock, processors, levels, edges, executors) {
          var dag = new StreamingDag(appId, clock, processors, levels, edges, executors);
          dag.replaceProcessor = restapi.replaceDagProcessor;
          return dag;
        }
      };
    }])
;
