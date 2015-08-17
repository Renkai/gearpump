/*
 * Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */
angular.module('dashboard')

  .controller('StreamingAppDagEditCtrl', ['$scope',
    function($scope) {
      'use strict';

      var options = $scope.modifyOptions || {};
      $scope.changeParallelismOnly = options.parallelism;
      var processor = $scope.activeProcessor;
      $scope.processorId = processor.id;
      $scope.taskClass = processor.taskClass;
      $scope.description = processor.description;
      $scope.parallelism = processor.parallelism;

      $scope.fillDefaultTime = function() {
        if (!$scope.transitTime) {
          $scope.transitTime = moment($scope.app.clock).format('HH:mm:ss');
        }
      };

      $scope.validParallelism = true;
      $scope.$watch('parallelism', function(val) {
        $scope.validParallelism = val && !isNaN(val);
      });

      $scope.validTaskClass = true;
      $scope.$watch('taskClass', function(val) {
        $scope.validTaskClass = val.length > 0 && /^[a-z_-][a-z\.\d_-]*[a-z\d_-]$/i.test(val);
      });

      $scope.canReplace = function() {
        return $scope.validParallelism && $scope.validTaskClass && $scope.isDirty();
      };

      $scope.isDirty = function() {
        // do not require same type!
        return $scope.taskClass != processor.taskClass ||
          $scope.description != processor.description ||
          $scope.parallelism != processor.parallelism ||
          $scope.transitTime || $scope.transitDate;
      };

      $scope.submit = function() {
        var newProcessor = {
          taskClass: $scope.taskClass,
          description: $scope.description,
          parallelism: $scope.parallelism
        };

        if ($scope.transitTime) {
          var isoDateTimeString = ($scope.transitDate || moment().format('YYYY-MM-DD')) + 'T' + $scope.transitTime;
          var transitUnixTime = moment(isoDateTimeString).valueOf();
          newProcessor.life = {
            birth: transitUnixTime.toString(),
            death: '9223372036854775807' /* Long.max */
          };
        }

        $scope.dag.replaceProcessor($scope.app.appId, $scope.processorId, newProcessor, function(response) {
          $scope.shouldNoticeSubmitFailed = !response.success;
          if (response.success) {
            $scope.$hide();
          } else {
            $scope.reason = response.reason;
          }
        });
      };
    }])
;