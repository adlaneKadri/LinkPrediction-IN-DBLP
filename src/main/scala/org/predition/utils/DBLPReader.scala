package org.predition.utils

import java.nio.file.{Files, Paths}
import org.apache.spark.sql.{Dataset, SparkSession}
import scala.collection.mutable

/**
  * Contient des méthodes qui permet de lire les données des fichiers dblp et d'extraire les informations relatives à la
  * procedure de prediction
  *
  * @author mohammedi
  */

object DBLPReader {
  /**
    * Extraire les liens de la relation Co-Author
    *
    * @example '''(2016, Hamza Labbaci, Youcef Aklouf)''' represete une relation Co-Author entre '''Hamza Labbaci''' et
    *          '''Youcef AKlouf''' durant l'année '''2016'''
    * @note cette méthode est creé de tels façon pour ne pas refaire le même travail a chaque fois, c'est pour ça elle
    *       sauvgarde les relation créés la première fois dans un dossier '''years''' pour les lire dans les prochaines
    *       eventuelles invocations
    *       SVP de ne pas supprimer ce dossier pour qu'on puisse optimizer le temps d'exection pour vous
    * @param spark la session courate du spark cette session est utilisé pour manipulé les `Dataset`
    * @param path  le lien du fichier dblp téléchargé de [[https://aminer.org/citation]]
    * @return liens de la relation Co-Author sous la form (année, Couple(nom du 1er auteur, nom du 2eme auteur))
    * @author mohammedi
    */
  def extractCoAuthorRelations(spark: SparkSession, path: String): Dataset[(Int, Couple)] = {
    import spark.implicits._ 
    if (Files.notExists(Paths.get("years"))) { //verifier si le fichier est déja enregistré
      /*
      verifier c'est une ligne d'auteurs contient un seul auteur
      cette méthode est utilisé pour ignorer ces dernier parcequ'elles ne contient aucune relation Co-Author
       */
      def notAlone(authors: String) = authors.substring(2).split(',').filterNot(_.matches("[ ]*")).length > 1
      /*
      1. lecture du fichier dans un Dataset[String], String c'est les linges du fichier
      2. ignorer les lignes vides et celle qui contient les information des articles (titre, abstract ...) et laisser que
      les lignes des auteurs (qui contient la relation Co-Author) et celles des années
      Exemple:
      a partir d'une ligne d'auteurs comme ça : #@Hamza Labbaci, Youcef Aklouf on peur extraire q'il y a une relation
      Co-Author entre "Hamza Labbaci" et "Youcef Aklouf"
      3. pour chaque partition boucler sur les lignes du fichier (filtré) et :
          - chercher la ligne qui commece par "#@" {while (iterator.hasNext && line.startsWith("#@"))}
          - si la ligne qui vient juste après represente une année {if (line.startsWith("#t"))}
            on extraire les relations comme montré dans l'exemple, les associer avec la l'année  et les retourner dans
             le resulat {la boucle for}

      Après tous ça on obtient une Dataset[(Int, Couple)] {relations} qui contient tous les relations dans le fichier
      4. on sauvgarde le resultat obtenue dans le dossier "years"
       */

      val relations = spark.read.textFile(path)
        .filter(line => line.startsWith("#t") || (line.startsWith("#@") && notAlone(line)))
        .filter(!_.isEmpty)
        .mapPartitions { iterator =>
          val result = mutable.MutableList[(Int, Couple)]()
          while (iterator.hasNext) {
            var line = iterator.next()
            var authors = ""
            while (iterator.hasNext && line.startsWith("#@")) {
              authors = line.substring(2)
              line = iterator.next()
            }
            if (iterator.hasNext) {
              if (line.startsWith("#t")) {
                val year = line.substring(2).trim.toInt
                val authorsList = authors.split(',').filterNot(_.matches("[ ]*"))
                for (index1 <- 0 until (authorsList.length - 1); index2 <- index1 + 1 until authorsList.length) {
                  val c = (year, Couple(authorsList(index1), authorsList(index2)))
                  result += c
                }
              }
            }
          }
          result.toIterator
        }
      relations.write.parquet("years")
      relations
    }.distinct()
    else spark.read.parquet("years").as[(Int, Couple)] //si le fichier exist on le lire directement
  }
}
